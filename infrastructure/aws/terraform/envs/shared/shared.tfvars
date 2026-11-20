# Account-level resources (ECR, CodeArtifact, CI role). Apply once, before the environments.
aws_region        = "ap-south-1"
ci_principal_arns = [] # REPLACE: e.g. ["arn:aws:iam::111111111111:role/jenkins"]
pull_account_ids  = [] # other accounts running staging/prod, if any
