<h1 align="center">
  <img src="https://raw.githubusercontent.com/11ways/hohenheim/master/app/root/startup.svg" width=30 alt="Hohenheim logo"/>
  <b>Hohenheim</b>
</h1>
<div align="center">
  Hohenheim is a web server and reverse proxy for node.js
</div>
<div align="center">
  <sub>
    Coded with ❤️ by <a href="#authors">Eleven Ways</a>.
  </sub>
</div>

## Requirements

### Node.js

Hohenheim requires at least node.js version 10.21.0

### Mongodb

You will need a mongodb server.

### n

Although technically not required, you can configure your sites to use a specific node.js version installed through [the **n** node version manager](https://github.com/tj/n)

### Capabilities

Hohenheim requires that your node.js binary has some extra capabilities. These are:

* `cap_setuid`: for setting the uid of the instances it spawns
* `cap_setgid`: for setting the gid of the instances it spawns
* `cap_kill`: for killing spawned instances with another uid than its own
* `cap_net_bind_service`: for binding to privileged ports, like port 80 & 443

(If you prefer to route port 80 & 443 to another port, you can drop `cap_net_bind_service`)

It's best to give hohenheim its own node executable, otherwise all scripts running would have these capabilities.

Here's an easy example on how to create a new node binary (your locations may differ)

```bash
sudo cp /usr/local/bin/node /usr/local/bin/hohenode
```

That's easy. Now give it the required capabilities:

```bash
sudo setcap 'cap_kill,cap_setuid,cap_setgid,cap_net_bind_service=+ep' /usr/local/bin/hohenode
```

Should you ever want to remove all capabilities from the binary, you can do so like this:

```bash
sudo setcap -r /usr/local/bin/hohenode
```

## Configuration

You will need to configure the following files

### app/config/local.js

```javascript
module.exports = {

    // The main port to listen on
    proxyPort: 80,

    // The main port to listen on for HTTPS/http2 traffic
    proxyPortHttps: 443,

    // Your current environment. Can be dev, preview or live
    environment: 'live',

    // When no sites match, this address will be tried last
    // (This can be your apache server, for instance)
    fallbackAddress: 'http://localhost:8080',

    // The host hohenheim will use to access the spawned node sites,
    // this should probably remain "localhost"
    redirectHost: 'localhost',

    // The first port to use for child node instances
    firstPort: 4748,

    // This is the port the admin interface listens on
    port: 2999,

    // Set to true to enable letsencrypt
    letsencrypt: true,

    // The default e-mail address to use for letsencrypt registrations
    letsencrypt_email: 'your@email.address',

    // Add the ipv6 address you want to listen on
    ipv6Address: ''
};
```

### app/config/dev/database.js or app/config/live/database.js

You'll find the database settings here, by default these are:

```javascript
Datasource.create('mongo', 'default', {
    host     : '127.0.0.1',
    database : 'hohenheim-live',
    login    : false,
    password : false
});
```

### Admin interface

Once you have everything configured and running, you can go to the admin interface at http://localhost:2999/chimera

The default credentials are `admin:admin`

### HTTPS & HTTP/2

If you want https & http/2 support, you need to set `letsencrypt: true` in your local configuration.

If you want to use your own certificates (and not letsencrypt), the `greenlock` module we use lets you do that.
You just need to put your own certificate files into the correct directory.

Eg: if you have your own certificates for the domain `example.com`, you can put them here:

```
~/hohenheim/temp/letsencrypt/etc/acme/live/example.com/privkey.pem
~/hohenheim/temp/letsencrypt/etc/acme/live/example.com/cert.pem
~/hohenheim/temp/letsencrypt/etc/acme/live/example.com/chain.pem
~/hohenheim/temp/letsencrypt/etc/acme/live/example.com/fullchain.pem
~/hohenheim/temp/letsencrypt/etc/acme/live/example.com/bundle.pem
```

## Systemd

Keep hohenheim running by setting up a Systemd service, for example:

```bash
sudo nano /etc/systemd/system/hohenheim.service
```

And then enter

```
[Unit]
Description=Hohenheim site dispatcher
After=mongodb.service

[Service]
WorkingDirectory=/home/www-data/hohenheim/
ExecStart=/usr/local/bin/hohenode /path/to/your/hohenheim/server.js
Restart=always
StandardOutput=syslog
StandardError=syslog
SyslogIdentifier=hohenheim
User=www-data
Group=www-data
Environment=NODE_ENV=production

[Install]
WantedBy=multi-user.target
```

You will need to change:

* `After`: Other services to wait for (in this case mongodb)
* `WorkingDirectory`: The path to the directory where the server.js file is
* `ExecStart`: The path to the capabilities-enabled node binary + the server.js file
* `User` and `Group`: The user you want to run hohenheim as
* `Environment`: Your own environment variables

Finally, enable it:

```bash
sudo systemctl enable hohenheim.service
```

### Using screen

Another interesting way to run hohenheim is to add `screen`. This will give you access to hohenheim through `janeway`:

```
[Unit]
Description=hohenheim

[Service]
Type=forking
User=skerit
Restart=always
ExecStart=/usr/bin/screen -d -m -S hohenheim -d -m /usr/local/bin/hohenode server.js
ExecStop=/usr/bin/killall -w -s 2 hohenheim
WorkingDirectory=/home/www-data/hohenheim/

[Install]
WantedBy=multi-user.target
```

Now, if you want to access the hohenheim shell, you can do:

```
screen -r hohenheim
```

## Node versions

You can configure your websites to use a specific node.js version, these versions are available:

* The system node binary (`which node` result)
* The binary `/usr/bin/node` if available
* The binary `/usr/local/bin/node` if available
* All global installed versions through the `n` module

If a configured version is not found, the system node binary will be used.

## Thanks

Many thanks go out to [Félix "passcod" Saparelli](https://github.com/passcod) who allowed me to use the `hohenheim` package name on npm.