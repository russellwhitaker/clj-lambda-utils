(ns clj-lambda.aws
  (:require [cheshire.core :refer [generate-string]]
            [clojure.string :as string])
  (:import [com.amazonaws.auth DefaultAWSCredentialsProviderChain]
           [com.amazonaws.services.identitymanagement AmazonIdentityManagementClient]
           [com.amazonaws.services.identitymanagement.model AttachRolePolicyRequest
                                                            CreatePolicyRequest
                                                            CreateRoleRequest
                                                            DeleteRoleRequest
                                                            DeletePolicyRequest
                                                            EntityAlreadyExistsException
                                                            GetRoleRequest
                                                            ListRolePoliciesRequest
                                                            DetachRolePolicyRequest]
           [com.amazonaws.services.lambda.model CreateFunctionRequest
                                                UpdateFunctionCodeRequest
                                                FunctionCode]
           [com.amazonaws.services.lambda AWSLambdaClient]
           [com.amazonaws.services.s3 AmazonS3Client]
           [com.amazonaws.services.apigateway AmazonApiGatewayClient]
           [com.amazonaws.services.apigateway.model CreateRestApiRequest
                                                    CreateResourceRequest
                                                    CreateDeploymentRequest
                                                    GetResourcesRequest
                                                    PutIntegrationRequest
                                                    PutMethodRequest]
           [com.amazonaws.regions Regions]
           [java.io File]))

(def aws-credentials
  (.getCredentials (DefaultAWSCredentialsProviderChain.)))

(defonce s3-client
  (delay (AmazonS3Client. aws-credentials)))

(defn- create-api-gateway-client [region]
  (-> (AmazonApiGatewayClient. aws-credentials)
      (.withRegion (Regions/fromName region))))

(defonce iam-client
  (delay (AmazonIdentityManagementClient. aws-credentials)))

(defn- create-lambda-client [region]
  (-> (AWSLambdaClient. aws-credentials)
      (.withRegion (Regions/fromName region))))

(defn- trust-policy [service]
  {:Version "2012-10-17"
   :Statement {:Effect "Allow"
               :Principal {:Service service}
               :Action "sts:AssumeRole"}})

(defn log-policy-with-statements [additional-statements]
  {:Version "2012-10-17"
   :Statement (concat [{:Effect "Allow"
                        :Action ["logs:CreateLogGroup"
                                 "logs:CreateLogStream"
                                 "logs:PutLogEvents"]
                        :Resource ["arn:aws:logs:*:*:*"]}]
                      additional-statements)})

(defn lambda-invoke-policy [account-id region function-name]
  {:Version "2012-10-17"
   :Statement [{:Effect "Allow"
                :Action ["lambda:InvokeFunction"]
                :Resource [(str "arn:aws:lambda:"
                               region
                               ":"
                               account-id
                               ":function:"
                               function-name)]}]})

(defn create-bucket-if-needed [bucket-name region]
  (if (.doesBucketExist @s3-client bucket-name)
    (println bucket-name "S3 bucket already exists. Skipping creation.")
    (do (println "Creating bucket" bucket-name "in region" region ".")
        (if (= "us-east-1" region)
          (.createBucket @s3-client bucket-name)
          (.createBucket @s3-client bucket-name region)))))

(defn create-role-and-policy [role-name policy-name trust-policy-service policy]
  (println "Creating role" role-name "with policy" policy-name "and statements" policy)
  (try
    (let [role (.createRole @iam-client (-> (CreateRoleRequest.)
                                            (.withRoleName role-name)
                                            (.withAssumeRolePolicyDocument (generate-string (trust-policy trust-policy-service)))))
          policy-result (.createPolicy @iam-client (-> (CreatePolicyRequest.)
                                                       (.withPolicyName policy-name)
                                                       (.withPolicyDocument (generate-string policy))))]
      (.attachRolePolicy @iam-client (-> (AttachRolePolicyRequest.)
                                         (.withPolicyArn (-> policy-result .getPolicy .getArn))
                                         (.withRoleName role-name)))
      (-> role .getRole .getArn))
    (catch EntityAlreadyExistsException _
      (println "Note! Role" role-name "already exists.")
      (-> (.getRole @iam-client (-> (GetRoleRequest.)
                                    (.withRoleName role-name)))
          (.getRole)
          (.getArn)))))

(defn- create-rest-api [api-name region]
  (-> (.createRestApi (create-api-gateway-client region) (-> (CreateRestApiRequest.)
                                                             (.withName api-name)))
      (.getId)))

(defn- get-root-path-id [rest-api-id region]
  (let [raw-items (-> (.getResources (create-api-gateway-client region) (-> (GetResourcesRequest.)
                                                                            (.withRestApiId rest-api-id)))
                      (.getItems))]
    (-> (filter #(= "/" (.getPath %)) raw-items)
        (first)
        (.getId))))

(defn- create-proxy-resource [rest-api-id region]
  (-> (.createResource (create-api-gateway-client region) (-> (CreateResourceRequest.)
                                                              (.withParentId (get-root-path-id rest-api-id region))
                                                              (.withRestApiId rest-api-id)
                                                              (.withPathPart "{proxy+}")))
      (.getId)))

(defn- create-any-method [rest-api-id proxy-resource-id region]
  (.putMethod (create-api-gateway-client region) (-> (PutMethodRequest.)
                                                     (.withRestApiId rest-api-id)
                                                     (.withResourceId proxy-resource-id)
                                                     (.withHttpMethod "ANY")
                                                     (.withApiKeyRequired false)
                                                     (.withAuthorizationType "NONE")
                                                     (.withRequestParameters {"method.request.path.proxy" true}))))

(defn- create-integration [rest-api-id resource-id region function-name]
  (let [account-id (-> (.getUser @iam-client) (.getUser) (.getArn) (string/split #":") (nth 4))
        role-arn (create-role-and-policy (str "api-gateway-role-" rest-api-id)
                                         (str "api-gateway-role-policy-" rest-api-id)
                                         "apigateway.amazonaws.com"
                                         (lambda-invoke-policy account-id region function-name))]
    (Thread/sleep 1000) ; Role creation is async
    (println "Creating integration with role-arn" role-arn)
    (.putIntegration (create-api-gateway-client region) (-> (PutIntegrationRequest.)
                                                            (.withRestApiId rest-api-id)
                                                            (.withResourceId resource-id)
                                                            (.withHttpMethod "ANY")
                                                            (.withIntegrationHttpMethod "POST")
                                                            (.withPassthroughBehavior "WHEN_NO_MATCH")
                                                            (.withType "AWS_PROXY")
                                                            (.withUri (str "arn:aws:apigateway:"
                                                                           region
                                                                           ":lambda:path/2015-03-31/functions/arn:aws:lambda:"
                                                                           region
                                                                           ":"
                                                                           account-id
                                                                           ":function:"
                                                                           function-name
                                                                           "/invocations"))
                                                            (.withCacheKeyParameters ["method.request.path.proxy"])
                                                            (.withCacheNamespace "7wcnin")
                                                            (.withCredentials role-arn)))))

(defn- create-deployment [rest-api-id stage-name region]
  (.createDeployment (create-api-gateway-client "eu-west-1") (-> (CreateDeploymentRequest.)
                                                                 (.withRestApiId rest-api-id)
                                                                 (.withStageName stage-name)))
                     (str "https://" rest-api-id ".execute-api." region ".amazonaws.com/" stage-name))

(defn store-jar-to-bucket [^File jar-file bucket-name object-key]
  (println "Uploading code to S3 bucket" bucket-name "with name" object-key)
  (.putObject @s3-client
              bucket-name
              object-key
              jar-file))

(defn- setup-api-gateway [api-name region function-name]
  (println "Setting up API Gateway with api name" api-name)
  (let [rest-api-id (create-rest-api api-name region)
        resource-id (create-proxy-resource rest-api-id region)]
    (create-any-method rest-api-id resource-id region)
    (create-integration rest-api-id resource-id region function-name)
    (let [api-url (create-deployment rest-api-id "test" region)]
      (println "Deployed to" api-url))))

(defn create-lambda-fn [{:keys [function-name handler bucket-name object-key memory-size timeout region role-arn s3]}]
  (println "Creating Lambda function" function-name "to region" region)
  (let [client (create-lambda-client region)]
    (.createFunction client (-> (CreateFunctionRequest.)
                                (.withFunctionName function-name)
                                (.withMemorySize (int memory-size))
                                (.withTimeout (int timeout))
                                (.withRuntime "java8")
                                (.withHandler handler)
                                (.withCode (-> (FunctionCode.)
                                               (.withS3Bucket (:bucket s3))
                                               (.withS3Key (:object-key s3))))
                                (.withRole role-arn)))))


(defn update-lambda-fn [lambda-name bucket-name region object-key]
  (println "Updating Lambda function" lambda-name "in region" region)
  (let [client (create-lambda-client region)]
    (.updateFunctionCode client (-> (UpdateFunctionCodeRequest.)
                                    (.withFunctionName lambda-name)
                                    (.withS3Bucket bucket-name)
                                    (.withS3Key object-key)))))

(defn update-lambda [deployments jar-file]
  (doseq [{:keys [region function-name s3]} deployments]
      (let [{:keys [bucket object-key]} s3]
        (println "Deploying to region" region)
        (store-jar-to-bucket (File. jar-file)
                             bucket
                             object-key)
        (update-lambda-fn function-name bucket region object-key))))

(defn install-lambda [deployments jar-file]
  (doseq [{:keys [api-gateway region function-name handler memory-size timeout s3 policy-statements] :as deployment} deployments]
      (let [{:keys [bucket object-key]} s3
            role-arn (create-role-and-policy (str function-name "-role")
                                             (str function-name "-policy")
                                             "lambda.amazonaws.com"
                                             (log-policy-with-statements policy-statements))]

        (println "Installing to region" region "with deployment" deployment)
        (create-bucket-if-needed bucket region)
        (store-jar-to-bucket (File. jar-file)
                             bucket
                             object-key)
        (create-lambda-fn (assoc deployment :role-arn role-arn))
        (when api-gateway
          (setup-api-gateway (:name api-gateway) region function-name)))))
