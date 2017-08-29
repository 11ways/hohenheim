# Hohenheim

Hohenheim is a web server and reverse proxy, mainly for node.js sites.

## Requirements

### Node.js

Hohenheim requires at least node.js version 6.6.0. Your sites can use lower versions if really needed, though.

### Mongodb

You will need a mongodb server.

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
