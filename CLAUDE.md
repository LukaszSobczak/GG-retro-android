# GG Retro na Androida – zasady pracy

Klient Gadu-Gadu 6.1 na Androida dla serwera gg-retro (https://github.com/webs1821/gg-retro).
Opis projektu, budowania i wydań: `README.md`.

## Gałęzie i PR (obowiązkowe)

1. Każdy nowy feature / poprawka: nowa gałąź `feature/<krótka-nazwa>` z **aktualnego `develop`**.
2. Commituj i pushuj na tę gałąź na bieżąco. CI (`.github/workflows/android.yml`) musi być zielone.
3. Push gałęzi `feature/**` sam otwiera PR do `develop` jako `github-actions[bot]`
   (`.github/workflows/auto-pr.yml`), żeby właściciel mógł go zatwierdzić. Po otwarciu uzupełnij tytuł i
   opis PR. Nie otwieraj PR z własnego (czyli właściciela) konta.
4. Gdy feature jest gotowy: daj znać i **czekaj na approve właściciela w PR**. Bez approve nie merguj.
   Przed merge sprawdź recenzje PR.
5. Po approve: merge metodą **squash** do `develop` (jeden liniowy commit), potem usuń gałąź feature.
6. Nie pushuj bezpośrednio na `develop` ani `master`.
7. Wydanie (tylko na prośbę właściciela): PR `develop` → `master` jako **merge commit** (nie squash),
   potem tag `X.Y.Z` na `master` → `.github/workflows/release.yml` publikuje Release z APK.
   Sesja Claude nie może pushować tagów; tag tworzy właściciel.

## Projekt

- `gg-protocol/` – protokół GG 6.0 (czysty Kotlin/JVM); testy lokalnie: `./gradlew -p gg-protocol test`.
- `app/` – aplikacja (Compose, Room, foreground service). Android SDK jest dostępny tylko w CI.
- `buildSrc/` – wyciąganie emotek/dźwięków/skórki z `gg61.exe` przy buildzie (nie commitujemy tych plików).
- Ikona aplikacji: `art/gg.png` → `python3 art/make_icons.py`.
- Serwer gg-retro zawsze odpowiada ACK „queued” (GG6 nie ma potwierdzeń odbioru).
