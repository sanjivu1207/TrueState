# PostgreSQL 16 on RDS, private, reachable only from the app instance.
#
# The master password is an *ephemeral* value: Terraform generates it, writes
# it to RDS and to SSM Parameter Store through write-only arguments, and never
# stores it in state or plan files. The instance reads it from SSM at deploy
# time. (Parameter Store standard tier is free; Secrets Manager would be
# $0.40/secret/month.)

ephemeral "random_password" "db" {
  length  = 32
  special = false # avoids URL/shell quoting problems; 32 alphanumerics is ~190 bits
}

resource "aws_ssm_parameter" "db_password" {
  name             = "/truestate/db/password"
  type             = "SecureString" # encrypted with the free AWS-managed aws/ssm key
  value_wo         = ephemeral.random_password.db.result
  value_wo_version = var.db_password_version
}

resource "aws_db_subnet_group" "main" {
  name       = "truestate"
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_security_group" "db" {
  name        = "truestate-db"
  description = "PostgreSQL from the app instance only"
  vpc_id      = aws_vpc.main.id
}

resource "aws_vpc_security_group_ingress_rule" "db_from_app" {
  security_group_id            = aws_security_group.db.id
  referenced_security_group_id = aws_security_group.app.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}

resource "aws_db_instance" "main" {
  identifier     = "truestate"
  engine         = "postgres"
  engine_version = "16"
  instance_class = var.db_instance_class

  # Free tier: 20 GB of General Purpose SSD.
  allocated_storage = 20
  storage_type      = "gp2"
  storage_encrypted = true

  db_name             = "payments"
  username            = "payments"
  password_wo         = ephemeral.random_password.db.result
  password_wo_version = var.db_password_version

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.db.id]
  publicly_accessible    = false
  multi_az               = false # Multi-AZ doubles the cost and is not free-tier

  backup_retention_period    = 1 # backup storage up to the DB size is free
  auto_minor_version_upgrade = true
  apply_immediately          = true

  # Demo environment: `terraform destroy` should leave nothing billable behind.
  skip_final_snapshot = true
  deletion_protection = false
}
