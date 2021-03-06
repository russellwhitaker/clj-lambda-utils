# clj-lambda-utils

Clojure utilities for deploying AWS Lambda (JVM) function(s) to one or multiple regions via S3.

Note! The name of the repo used to be ```lein-clj-lambda``` when it contained only the Leingingen plugin.

## Usage

You can use utilities as a Leiningen plugin or just as a Clojure library. Boot support is coming up later!

### Leiningen plugin

Note! Uninstalling Lambda is not currently supported so you have to delete all resources manually if you need to uninstall Lambda. When installing Lambda all created resource names are logged to console.

Put `[lein-clj-lambda "0.9.1"]` into the `:plugins` vector of your project.clj (or your profile if you prefer that).

Create S3 bucket and create following configuration into `project.clj`

```clojure
:lambda {"test" [{:api-gateway {:name "DemoApi"} ; Optional, if you want to access via API Gateway
                  :handler "lambda-demo.LambdaFn"
                  :memory-size 512
                  :timeout 60
                  :function-name "my-func-test"
                  :environment {"MY_ENVIRONMENT_VAR" "some value" ;Optional
                                "SOME_OTHER_ENV_VAR" "another val"}
                  :region "eu-west-1"
                  :policy-statements [{:Effect "Allow"
                                       :Action ["sqs:*"]
                                       :Resource ["arn:aws:sqs:eu-west-1:*"]}]
                  :s3 {:bucket "your-bucket"  ; Optional, if not specified default bucket will be generated
                       :object-key "lambda.jar"}}]
          "production" [{:handler "lambda-demo.LambdaFn"
                         :memory-size 1024
                         :timeout 300
                         :function-name "my-func-prod"
                         :environment {"MY_ENVIRONMENT_VAR" "some value"
                                       "SOME_OTHER_ENV_VAR" "another val"}
                         :region "eu-west-1"
                         :s3 {:bucket "your-bucket"
                              :object-key "lambda.jar"}}]}
```

Then run

    $ lein lambda install test

or

    $ lein lambda install production

This will create S3 buckets that will be used for uploading code to Lambda.
Also, this creates new IAM roles and policies so the Lambda function can write to
Cloudwatch logs. If you need to set up additional access rights, you can pass
`:policy-statements`. The format of statements are specified in a Clojure EDN map
but they will be passed as JSON to AWS IAM (See here the details of policy
statements http://docs.aws.amazon.com/IAM/latest/UserGuide/reference_policies_elements.html#Statement)
If an S3 bucket or role already exists, its creation will be skipped.

Note that IAM role and policy creation happen asynchronously. Sometimes they take too much
time to create and they are not ready when a Lambda function is installing. In those cases
you will get an error like `The role defined for the function cannot be assumed by Lambda.`
Just retrying the install command should fix this issue.

If the Lambda function already exists and you want to just configure API gateway for that you can run:

    $ lein lambda install test --only-api-gateway

After the Lambda function is installed you should not run `install` anymore but instead just run the
`update` task to update the latest code to Lambda environment.

    $ lein lambda update test

or

    $ lein lambda update production

### Library

Add the following to your `project.clj` `:dependencies`:

```clojure
[clj-lambda "0.5.1"]
```

Then run

```clojure
(require '[clj-lambda.aws :as aws])

(aws/update-lambda stage-name config jar-file opts)

(aws/install-lambda stage-name config jar-file opts)
```

where `config` is a vector of configuration options; see the example from the Leiningen plugin documentation above.

## License

Copyright © 2016-2017 Markus Hjort

Distributed under the Eclipse Public License 1.0.
