# Stable development signing

This directory contains a development-only signing keystore encoded as Base64 so CI and local debug builds can use the same certificate across builds.

The key is intentionally for development/testing only. Do not use it to sign a production release. Production signing must use a private keystore stored outside the repository (for example, encrypted CI secrets or a secure signing service).
