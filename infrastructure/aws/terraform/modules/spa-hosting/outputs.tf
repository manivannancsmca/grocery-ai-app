output "bucket" {
  description = "Web bucket (releases/<sha>/)."
  value       = aws_s3_bucket.web.id
}

output "bucket_arn" {
  description = "Web bucket ARN."
  value       = aws_s3_bucket.web.arn
}

output "bucket_kms_key_arn" {
  description = "KMS key of the web bucket (CI needs encrypt/decrypt to upload)."
  value       = aws_kms_key.web.arn
}

output "distribution_id" {
  description = "CloudFront distribution id."
  value       = aws_cloudfront_distribution.this.id
}

output "distribution_arn" {
  description = "CloudFront distribution ARN."
  value       = aws_cloudfront_distribution.this.arn
}

output "distribution_domain_name" {
  description = "*.cloudfront.net name."
  value       = aws_cloudfront_distribution.this.domain_name
}
