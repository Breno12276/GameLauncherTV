# Nova Home

Tela inicial para Android no estilo do Android TV, feita para usar com controle: jogos de Android, ROMs de emulador, filmes, séries e trailers em fileiras.

![Tela Início](docs/img/inicio.png)

**Página do projeto:** https://SEU-USUARIO.github.io/NovaHome/
**Baixar:** [última versão](https://github.com/SEU-USUARIO/NovaHome/releases/latest) — use o `NovaHome-X.Y-publico.apk` (Android 13+).

## Recursos

- Abas **Início**, **Jogos** e **Apps**, navegáveis pelo direcional.
- ROMs de 18 plataformas (PS1 a PS4, PSP, Vita, Switch, Wii U, GameCube/Wii, 3DS, DS, N64, GBA, SNES, NES, Mega Drive, Dreamcast, Xbox 360), cada uma aberta no emulador certo.
- Capas automáticas (libretro, Steam, GameTDB, SteamGridDB…) e troca manual de capa.
- Página de informações de cada jogo: sinopse em português, ficha, galeria, trailer e conquistas do RetroAchievements.
- Trailers automáticos no fundo, com tela cheia quando o controle fica parado.
- Fileiras de filmes e séries do Stremio, Apple, Netflix (TMDB) e YouTube.
- Paisagens do Wikimedia Commons como fundo e descanso de tela.
- Luz e vibração do DualShock 4 / DualSense.
- Funciona sem nenhuma chave de API; chaves grátis (TMDB, SteamGridDB, YouTube, RetroAchievements) completam o resto e podem ser coladas pelo celular via QR code.

## Compilar

```bash
./gradlew assemblePublicoRelease
```

A variante `pessoal` lê `TMDB_KEY`, `STEAMGRIDDB_KEY` e `YOUTUBE_KEY` de um `segredos.properties` na raiz, que fica fora do Git.

---

Projeto pessoal, sem relação com os serviços e marcas citados. Este produto usa a API do TMDB, mas não é endossado nem certificado pelo TMDB.
