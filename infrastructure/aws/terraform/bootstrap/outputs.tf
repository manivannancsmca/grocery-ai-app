output "backend_config" {
  description = "Contents for envs/*/backend.hcl."
  value = {
    bucket         = aws_s3_bucket.state.id
    region         = local.region
    kms_key_id     = aws_kms_alias.state.name
    dynamodb_table = aws_dynamodb_table.locks.name
  }
}
