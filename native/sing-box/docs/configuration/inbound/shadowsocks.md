### Structure

```json
{
  "type": "shadowsocks",
  "tag": "ss-in",

  ... // Listen Fields

  "method": "2022-blake3-aes-128-gcm",
  "password": "example-password",
  "managed": false,
  "multiplex": {}
}
```

### Multi-User Structure

```json
{
  "method": "2022-blake3-aes-128-gcm",
  "password": "example-password",
  "users": [
    {
      "name": "sekai",
      "password": "example-psk"
    }
  ],
  "multiplex": {}
}
```

### Relay Structure

```json
{
  "type": "shadowsocks",
  "method": "2022-blake3-aes-128-gcm",
  "password": "example-password",
  "destinations": [
    {
      "name": "test",
      "server": "example.com",
      "server_port": 8080,
      "password": "example-psk"
    }
  ],
  "multiplex": {}
}
```

### Listen Fields

See [Listen Fields](/configuration/shared/listen/) for details.

### Fields

#### network

Listen network, one of `tcp` `udp`.

Both if empty.

#### method

==Required==

| Method                        | Key Length |
|-------------------------------|------------|
| 2022-blake3-aes-128-gcm       | 16         |
| 2022-blake3-aes-256-gcm       | 32         |
| 2022-blake3-chacha20-poly1305 | 32         |
| none                          | /          |
| aes-128-gcm                   | /          |
| aes-192-gcm                   | /          |
| aes-256-gcm                   | /          |
| chacha20-ietf-poly1305        | /          |
| xchacha20-ietf-poly1305       | /          |

#### password

==Required==

| Method        | Password Format                                |
|---------------|------------------------------------------------|
| none          | /                                              |
| 2022 methods  | `sing-box generate rand --base64 <Key Length>` |
| other methods | any string                                     |

#### managed

Defaults to `false`. Enable this when the inbound is managed by the [SSM API](/configuration/service/ssm-api) for dynamic user.

#### multiplex

See [Multiplex](/configuration/shared/multiplex#inbound) for details.
