# Values from the bootstrap outputs (terraform -chdir=../../bootstrap output). Replace <ACCOUNT_ID>.
bucket         = "grocery-tfstate-<ACCOUNT_ID>-ap-south-1"
region         = "ap-south-1"
kms_key_id     = "alias/grocery-tfstate"
dynamodb_table = "grocery-tfstate-locks" # contract §3 lock table; S3 native locking (use_lockfile) is also on
