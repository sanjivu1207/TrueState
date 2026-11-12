# One free-tier EC2 instance running the Java service in Docker.
#
# No SSH: there is no key pair and port 22 is closed. Shell access and
# deployments both go through SSM (Session Manager / Run Command), which is
# authenticated with IAM and logged.

data "aws_ssm_parameter" "al2023_ami" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

# Only CloudFront may reach the instance; everyone else gets HTTPS via CloudFront.
data "aws_ec2_managed_prefix_list" "cloudfront" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

resource "aws_security_group" "app" {
  name        = "truestate-app"
  description = "HTTP from CloudFront only; all outbound"
  vpc_id      = aws_vpc.main.id
}

resource "aws_vpc_security_group_ingress_rule" "app_http_from_cloudfront" {
  security_group_id = aws_security_group.app.id
  prefix_list_id    = data.aws_ec2_managed_prefix_list.cloudfront.id
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
}

resource "aws_vpc_security_group_egress_rule" "app_all_out" {
  security_group_id = aws_security_group.app.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

# --- instance role: SSM agent + read the one DB password parameter ---

data "aws_iam_policy_document" "ec2_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "app" {
  name               = "truestate-app"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json
}

resource "aws_iam_role_policy_attachment" "app_ssm" {
  role       = aws_iam_role.app.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

data "aws_iam_policy_document" "app_read_db_password" {
  statement {
    actions   = ["ssm:GetParameter"]
    resources = [aws_ssm_parameter.db_password.arn]
  }
}

resource "aws_iam_role_policy" "app_read_db_password" {
  name   = "read-db-password"
  role   = aws_iam_role.app.id
  policy = data.aws_iam_policy_document.app_read_db_password.json
}

resource "aws_iam_instance_profile" "app" {
  name = "truestate-app"
  role = aws_iam_role.app.name
}

# --- the instance ---

resource "aws_instance" "app" {
  ami                    = data.aws_ssm_parameter.al2023_ami.value
  instance_type          = var.instance_type
  subnet_id              = aws_subnet.public.id
  vpc_security_group_ids = [aws_security_group.app.id]
  iam_instance_profile   = aws_iam_instance_profile.app.name

  metadata_options {
    http_tokens = "required" # IMDSv2 only
  }

  root_block_device {
    volume_size = 30 # free tier covers 30 GB of EBS
    volume_type = "gp3"
    encrypted   = true
  }

  user_data = templatefile("${path.module}/user_data.sh.tftpl", {
    region            = var.aws_region
    image_repository  = var.image_repository
    db_host           = aws_db_instance.main.address
    db_name           = aws_db_instance.main.db_name
    db_user           = aws_db_instance.main.username
    db_password_param = aws_ssm_parameter.db_password.name
  })
  user_data_replace_on_change = true

  lifecycle {
    # A newer AMI should not silently replace the running server.
    ignore_changes = [ami]
  }

  tags = { Name = "truestate-app" }
}
