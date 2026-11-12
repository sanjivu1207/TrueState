variable "aws_region" {
  description = "Region for everything except CloudFront (which is global)."
  type        = string
  default     = "us-east-1"
}

variable "github_repo" {
  description = "owner/name of the GitHub repo allowed to deploy via OIDC."
  type        = string
  default     = "sanjivu1207/TrueState"
}

variable "image_repository" {
  description = "Container image the instance runs (tag is chosen at deploy time)."
  type        = string
  default     = "ghcr.io/sanjivu1207/truestate-java"
}

variable "create_github_oidc_provider" {
  description = "Set false if this AWS account already has the token.actions.githubusercontent.com OIDC provider."
  type        = bool
  default     = true
}

variable "instance_type" {
  description = "Free-tier eligible x86 instance (the CI image is built for linux/amd64)."
  type        = string
  default     = "t3.micro"
}

variable "db_instance_class" {
  description = "Free-tier eligible RDS instance class."
  type        = string
  default     = "db.t4g.micro"
}

variable "db_password_version" {
  description = "Bump to rotate the database password on the next apply."
  type        = number
  default     = 1
}
