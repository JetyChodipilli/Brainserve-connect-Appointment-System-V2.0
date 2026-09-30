# BrainServe environment setup

Copy the included files into the same relative locations in the repository.

Replace every `CHANGE_ME...` value inside `backend/.env` before startup.

The backend reads `.env` because `application.properties` contains:

```properties
spring.config.import=optional:file:.env[.properties],optional:file:backend/.env[.properties]
```

Start from the backend directory:

```powershell
cd D:\Brainserve-connect-Appointment-System\backend
mvn spring-boot:run
```

Start the frontend in another terminal:

```powershell
cd D:\Brainserve-connect-Appointment-System
npm run dev
```

Routes remain unchanged at `http://localhost:8080/api/v1/...`.

Generate replacement Base64 secrets in PowerShell:

```powershell
function New-Base64Secret {
    param([int]$Bytes = 32)
    $buffer = New-Object byte[] $Bytes
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($buffer)
    [Convert]::ToBase64String($buffer)
}

New-Base64Secret
```

Use different generated values for JWT, PII encryption, QR signing, and archive encryption.
For archive encryption keep the prefix:

```env
ARCHIVE_ENCRYPTION_KEYS=v1=<generated-value>
```

Verify Git ignores local secrets:

```powershell
git check-ignore -v backend/.env
git check-ignore -v .env.local
git status --short
```

Credentials previously committed to Git remain in history. Rotate them before reuse.
