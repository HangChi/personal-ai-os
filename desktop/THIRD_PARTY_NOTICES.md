# Desktop third-party notices

The desktop bundle contains the Apache-2.0 Personal AI OS application and its existing
Java/frontend dependencies, plus the following desktop/runtime components:

| Component | Version | License / source |
| --- | --- | --- |
| Electron | 45.2.3 | MIT; Chromium and Node.js licenses are distributed with Electron |
| Eclipse Temurin JRE | 21.0.13+9 | GPLv2 with Classpath Exception; `jre/legal/` and `jre/NOTICE` |
| pg0 | 0.16.1 | MIT; `licenses/pg0-MIT.txt`; https://github.com/vectorize-io/pg0/tree/v0.16.1 |
| PostgreSQL | 18.1 | PostgreSQL License; `licenses/PostgreSQL.txt` |
| pgvector | 0.8.5, bundled by pg0 | PostgreSQL License; `licenses/pgvector.txt` |

Temurin and pg0 downloads are pinned by URL and SHA-256 in `runtime-lock.json`.
pg0 carries its PostgreSQL distribution and extension payloads. Its upstream source
and build configuration identify their bundled library dependencies; their notices
remain in the extracted distribution. The JRE legal directory is preserved in full.
Electron-builder is a build dependency, under MIT, and is not required on users' machines.

Application dependency versions remain in `backend/pom.xml` and
`frontend/package-lock.json`. This file does not replace those upstream licenses.
