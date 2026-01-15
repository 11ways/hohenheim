# Hohenheim Development Guide

## AlchemyMVC

@node_modules/alchemymvc/CLAUDE.md

## Overview

Hohenheim is a reverse proxy and site dispatcher. It routes incoming HTTP/HTTPS requests to different backends based on hostname, managing node.js processes, static files, or proxy targets.

## Commands
- Start server: `node server.js`
- Admin interface: `http://localhost:2999/chimera` (default credentials: `admin:admin`)

## Architecture

### Request Flow
1. `SiteDispatcher` receives all incoming requests on proxy ports (80/443)
2. Matches hostname against registered `Site` domains
3. Dispatches to the appropriate `Site` instance
4. Site handles request based on its type (spawn process, proxy, serve static, redirect)

### Class Group Pattern

Site uses Alchemy's **class group pattern** (see AlchemyMVC docs) for polymorphism. The `Site` lib class is the abstract base with group name `'site_type'`. Each site type (NodeSite, ProxySite, etc.) automatically registers and defines its own settings schema.

The Site model's `settings` field uses dynamic schema based on `site_type` - when you select "NodeSite", the settings form shows NodeSite-specific fields.

## Key Classes

### SiteDispatcher (`app/lib/site_dispatcher.js`)
- Inherits from `Informer` (not Alchemy.Base)
- Stores sites by: `this.ids`, `this.domains`, `this.names`
- Manages ports: `this.ports`, `this.firstPort`
- Handles SSL via Greenlock/Let's Encrypt
- Created in `server.js` as `alchemy.dispatcher`

### Site (`app/lib/site.js`)
- Abstract base class in `Develry` namespace
- Each instance wraps a Site model record
- Key methods: `matches(hostname, ip)`, `handleRequest(req, res)`

### Site Types (`app/lib/site/`)
- **NodeSite** - Spawns and manages node.js child processes
- **ProxySite** - Proxies requests to another address
- **StaticSite** - Serves static files via ecstatic
- **RedirectSite** - HTTP redirects
- **AlchemySite** - Extends NodeSite for Alchemy apps

## Models

### Site (`app/model/site_model.js`)
- `name` - Display name
- `site_type` - Enum from class group
- `settings` - Dynamic schema based on site_type
- `domain` - Array of domain configs (hostname, listen_on IPs, headers)
- Uses Sluggable behaviour

### Domain (`app/model/domain_model.js`)
- Links hostnames to sites

## Shared Data

Uses `alchemy.shared()` (see AlchemyMVC docs) to store:
- `'local_ips'` - Available IP addresses on this machine
- `'local_users'` - System users (for running site processes as different users)

## Configuration

**app/config/local.js:**
```javascript
module.exports = {
	proxyPort: 80,
	proxyPortHttps: 443,
	port: 2999,                 // Admin interface
	firstPort: 4748,            // First port for child processes
	fallbackAddress: 'http://localhost:8080',
	letsencrypt: true,
	letsencrypt_email: 'your@email.address',
};
```

## HTTPS / Certificates

Certificates are managed by Greenlock and stored in:
```
temp/letsencrypt/etc/acme/live/{domain}/
├── privkey.pem
├── cert.pem
├── chain.pem
├── fullchain.pem
└── bundle.pem
```

Custom certificates can be placed here manually instead of using Let's Encrypt.

## Directory Structure

```
app/
├── config/
├── controller/
├── element/
├── lib/
│   ├── site_dispatcher.js   # Main dispatcher class
│   ├── site.js              # Abstract Site base class
│   └── site/                # Site type implementations
│       ├── node_site.js     # Spawns node processes
│       ├── proxy_site.js    # Proxies to address
│       ├── static_site.js   # Serves static files
│       └── redirect_site.js # HTTP redirects
├── model/
│   ├── site_model.js        # Site configuration
│   └── domain_model.js      # Domain mappings
├── task/                    # Background tasks
└── view/
```
