# AUCTUS — Cheque Validation Platform

An internal banking platform for validating Tunisian cheques under the 2025
banking reform (QR code, ceiling amount, expiry date), built as Spring Boot
microservices behind an Angular front end.

Where TuniChèque serves the public, AUCTUS is the tool for **banking agent**
works in: it validates a cheque end to end, then carries the everyday branch
work that follows — the client file, credit applications, and the queue of
tasks passed between agents and their administrator.

---

## What it does

### Cheque validation

A cheque image is taken through five checks, and the agent is shown the
verdict of each rather than a single opaque answer:

| # | Check | Question it answers |
|---|-------|--------------------|
| 1 | **Document type** | Is this a cheque at all, or a photo of something else? |
| 2 | **QR extraction** | What does the printed QR code declare? |
| 3 | **Cross-validation** | Does the QR agree with the text printed on the cheque? |
| 4 | **Signature** | Does the signature match the one the bank holds for this account? |
| 5 | **Funds & compliance** | Can the account cover it, and is the cheque still valid? |

The QR and the printed fields are compared field by field, because a mismatch
between them is the signature of a tampered cheque.

### Signature verification

Three complementary measures are blended, because no single one separates a
skilled forgery on its own:

- **Shape correlation** — alignment-tolerant, so a small offset does not fail a genuine signature
- **Stroke direction** — the path the pen travelled, which forgers reproduce least faithfully
- **Local overlap** — block-by-block agreement, since a forgery matches globally but drifts locally

Measured over the full generated dataset: **every genuine signature accepted,
no forgery and no other signer accepted.**

### Branch operations

- **Client register** — search by RIB, name or account number; balance, signature specimen, cheque history
- **Five bank operations** — change account type (including minor → standard at 18), account status, contact details, credit applications, chequebook orders. Each one writes to the client file and is recorded in an audit trail.
- **Work queue** — tasks passed between agents and the administrator, with documents, deadlines and the client they concern
- **Messaging** — direct and group conversations with images and voice notes

---

## Architecture

```
┌────────────┐     ┌─────────────────┐
│  Angular   │────▶│   API Gateway   │  :8080
│   :4200    │     └────────┬────────┘
└────────────┘              │
                   ┌────────▼─────────┐
                   │ Service Registry │  :8761  (Eureka)
                   └────────┬─────────┘
     ┌──────────┬───────────┼───────────┬──────────┬──────────┐
     ▼          ▼           ▼           ▼          ▼          ▼
   Auth      Cheque       OCR          QR      Signature   Client
   :8081     :8082       :8083       :8084      :8085      :8086
                                                              │
                                                        Collaboration
                                                            :8087
```

| Service | Port | Responsibility |
|---------|------|----------------|
| `service-registry` | 8761 | Eureka discovery |
| `api-gateway` | 8080 | Single entry point, circuit breakers |
| `auth-service` | 8081 | Sign-in, BCrypt credentials, roles |
| `cheque-service` | 8082 | Validation orchestration, history, statistics |
| `ocr-service` | 8083 | Is this image a cheque? |
| `qr-service` | 8084 | QR reading and cross-validation |
| `signature-service` | 8085 | Signature matching |
| `client-service` | 8086 | Client file, funds, bank operations |
| `collab-service` | 8087 | Tasks, messaging, attachments |

**Stack** — Java 17 · Spring Boot 3.2 · Spring Cloud · PostgreSQL · Angular 15 · Python (OpenCV) for image work

---

## Running it

### Prerequisites

- JDK 17+, Maven 3.9+
- Node.js 18+
- PostgreSQL 14+
- Python 3.10+ with `opencv-python`, `numpy`, `pyzbar`

### Databases

```sql
CREATE DATABASE auth_db;
CREATE DATABASE cheque_db;
```

Tables are created automatically on first start (`ddl-auto: update`).

### Configuration

```bash
cp .env.example .env   # then fill in real values
```

Every service reads its settings from environment variables and falls back to a
local development default. `JWT_SECRET` has no default, by design.

### Start

```bash
cd backend && mvn clean install
# start service-registry first, then the rest, then:
cd frontend/auctus-frontend && npm install && npm start
```

The front end is served at `http://localhost:4200`.

---

## Repository layout

```
auctus-microservices/
├── backend/
│   ├── service-registry/     Eureka
│   ├── api-gateway/          routing + fallbacks
│   ├── auth-service/         authentication
│   ├── cheque-service/       validation orchestration
│   ├── ocr-service/          document classification
│   ├── qr-service/           QR + cross-validation   (+ Python)
│   ├── signature-service/    signature matching      (+ Python)
│   ├── client-service/       client file + operations
│   └── collab-service/       tasks, chat, attachments
└── frontend/auctus-frontend/
    └── src/app/
        ├── modules/auth      sign-in
        ├── modules/agent     agent dashboard, history, profile
        ├── modules/admin     oversight dashboard
        └── shared/           client register, messaging, work queue
```

## Branches

- `main` — stable
- `development` — integration
- `feature/structure` — feature work

---

## A note on data

No customer data is in this repository. Signature specimens, uploaded
documents, voice notes and generated datasets are excluded by `.gitignore`
and stay on the machine that produced them.
