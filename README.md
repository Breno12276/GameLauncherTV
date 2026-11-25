<h1 align="center">GameLauncherTV</h1>

<p align="center">
  <b>Seu celular com cara de console.</b><br>
  Tela inicial para Android no estilo do Android TV, feita para jogar com controle:<br>
  jogos, emuladores, filmes e trailers num só lugar.
</p>

<p align="center">
  <a href="https://github.com/Breno12276/GameLauncherTV/releases/latest"><b>⬇ Baixar o APK</b></a> ·
  <a href="https://breno12276.github.io/GameLauncherTV/">Site</a> ·
  <a href="#-recursos">Recursos</a> ·
  <a href="#-plataformas">Plataformas</a> ·
  <a href="#-como-instalar">Como instalar</a>
</p>

<p align="center">
  <img src="docs/img/inicio.png" alt="Tela Início do GameLauncherTV" width="900">
</p>

<p align="center">
  Android 13+ · APK de ~420 KB · sem anúncios · sem cadastro · funciona sem chave de API
</p>

---

## ✨ Recursos

<table>
  <tr>
    <td width="50%"><img src="docs/img/jogos.png" alt="Aba Jogos"></td>
    <td width="50%"><img src="docs/img/info-jogo.png" alt="Informações do jogo"></td>
  </tr>
  <tr>
    <td><b>🎮 Todos os jogos num lugar</b><br>Jogos de Android e ROMs de 18 plataformas, cada uma na sua fileira, com capa automática. Cada jogo abre direto no emulador certo.</td>
    <td><b>📖 Página de cada jogo</b><br>Sinopse em português, ficha técnica, selos, galeria de prints, trailer e conquistas do RetroAchievements.</td>
  </tr>
  <tr>
    <td><img src="docs/img/apps.png" alt="Aba Apps"></td>
    <td><img src="docs/img/trocar-capa.png" alt="Trocar capa"></td>
  </tr>
  <tr>
    <td><b>🧩 Apps em cores</b><br>Cada app num card na cor do próprio ícone, ou com o banner de Android TV que ele traz.</td>
    <td><b>🖼️ Trocar capa</b><br>Busque artes no libretro, GameTDB, Steam, App Store, Internet Archive ou SteamGridDB, ou use uma foto sua.</td>
  </tr>
  <tr>
    <td><img src="docs/img/configuracoes.png" alt="Configurações"></td>
    <td><img src="docs/img/descanso.png" alt="Descanso de tela"></td>
  </tr>
  <tr>
    <td><b>🎨 Do seu jeito</b><br>Tamanho e cantos dos cards, cores de fundo e de destaque (ou automáticas pelo tema do Android), ordem das fileiras.</td>
    <td><b>🏔️ Fundos e descanso de tela</b><br>Paisagens premiadas do Wikimedia Commons, com crédito, e relógio no descanso de tela.</td>
  </tr>
</table>

E também:

- 🎬 **Trailers automáticos:** pare o foco num jogo ou filme e o trailer toca no fundo; sem mexer no controle, ele vai para tela cheia.
- 🍿 **Filmes e séries em alta:** fileiras do Stremio, Apple, Netflix (via TMDB) e YouTube, com títulos em português.
- 🌈 **Luz e vibração do controle:** a barra de luz do DualShock 4 / DualSense acompanha a cor do jogo em foco.
- 📱 **Chaves pelo celular:** escaneie um QR code e cole as chaves no celular, sem digitar com o controle.

## 🕹️ Plataformas

O GameLauncherTV não emula nada: ele encontra suas ROMs e abre cada uma no emulador que você já tem.

| Plataforma | Emuladores reconhecidos |
|---|---|
| PlayStation · PS2 · PS3 · PS4 | DuckStation, NetherSX2/AetherSX2, aPS3e/RPCS3, shadPS4 |
| PSP · PS Vita | PPSSPP, Vita3K |
| Switch · Wii U · GameCube/Wii | Eden, Yuzu, Sudachi, Citron, Cemu, Dolphin |
| 3DS · DS | Azahar, Lime3DS, Citra, melonDS, DraStic |
| N64 · GBA · GB · SNES · NES · Mega Drive | RetroArch |
| Dreamcast · Xbox 360 | Flycast, Redream, aX360e |

Qualquer outro emulador pode ser escolhido em **Configurações → Emuladores → Outro app instalado…**

## 🎮 Controle

| Botão | Ação |
|---|---|
| R1 / L1 | Próxima aba / aba anterior |
| R2 / L2 | Pula vários cards |
| Start | Configurações |
| Select | Pesquisar |
| □ / X | Informações do jogo |
| △ / Y | Opções do card (renomear, capa, mover, esconder) |

## 🔑 Chaves de API (opcionais)

Sem nenhuma chave, o app usa só fontes livres: Steam, Wikipédia, libretro, GameTDB, Stremio, Apple e Wikimedia Commons. As chaves abaixo são grátis e completam o resto. Coloque-as em **Configurações → Contas e chaves**.

| Serviço | O que acrescenta |
|---|---|
| TMDB | Filmes e séries em português, fileiras da Netflix, trailers dublados |
| SteamGridDB | Capas, logos e artes que as fontes livres não têm |
| YouTube | Trailers de jogos fora da Steam e "Em alta no YouTube" |
| RetroAchievements | Suas conquistas nas ROMs |

As chaves ficam só no seu aparelho. O APK público não traz nenhuma chave.

## 📲 Como instalar

1. Baixe o `GameLauncherTV-X.Y-publico.apk` na [última versão](https://github.com/Breno12276/GameLauncherTV/releases/latest).
2. Instale, permitindo "instalar apps desconhecidos".
3. Abra pela gaveta de apps para experimentar.
4. Para virar a tela inicial: **Configurações → Sistema → Tela inicial padrão do celular** → GameLauncherTV.

## 🛠️ Compilar

Projeto Android em Kotlin (minSdk 33, compileSdk 36, Java 17).

```bash
./gradlew assemblePublicoRelease
```

A variante `pessoal` lê `TMDB_KEY`, `STEAMGRIDDB_KEY` e `YOUTUBE_KEY` de um `segredos.properties` na raiz, que fica fora do Git.

---

<sub>Projeto pessoal, sem relação com Google, Sony, Nintendo, Valve ou os serviços citados; as marcas e artes pertencem aos seus titulares. Este produto usa a API do TMDB, mas não é endossado nem certificado pelo TMDB. Paisagens: Wikimedia Commons (CC BY-SA).</sub>
