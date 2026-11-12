output "api_url" {
  value = "https://${aws_cloudfront_distribution.api.domain_name}"
}

output "swagger_url" {
  value = "https://${aws_cloudfront_distribution.api.domain_name}/docs"
}

# The next three go into GitHub repo *variables* (not secrets: none of them
# grants access by itself; the role trusts only this repo's main branch).
output "github_variables" {
  value = {
    AWS_REGION          = var.aws_region
    AWS_DEPLOY_ROLE_ARN = aws_iam_role.github_deploy.arn
    AWS_INSTANCE_ID     = aws_instance.app.id
    API_URL             = "https://${aws_cloudfront_distribution.api.domain_name}"
  }
}

output "db_endpoint" {
  value = aws_db_instance.main.address
}
