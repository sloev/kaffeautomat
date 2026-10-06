# Reference-server

Ét Python-script uden afhængigheder (Python 3.8+). Protokollen står i
[docs/design.md](../docs/design.md) afsnit 8.

```sh
AUTOMAT_TOKEN=lang-tilfældig-streng ADMIN_PASSWORD=hemmeligt NTFY_TOPIC=mit-emne python3 server.py
```

Sæt den bag en https-proxy, f.eks. Caddy:

```
automat.example.dk {
    reverse_proxy /api/* localhost:8080
    reverse_proxy localhost:8080
}
```

og i telefonens `config.json`:

```json
"server": { "url": "https://automat.example.dk/api", "token": "lang-tilfældig-streng", "heartbeatSec": 60 }
```

- `https://automat.example.dk/` – statusside (bruger `admin`), opdateres hvert 30. s.
- Alarmer via ntfy.sh: automat offline/online igen, refundering, fejl, nedbrud, fejlet kommando.
- Data i `data/devices.json` (seneste status) og `data/events.jsonl` (alle hændelser).

Det er en reference – byg gerne jeres egen server efter samme protokol.
