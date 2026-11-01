# GG Retro na Androida

Minimalny klient Gadu-Gadu na Androida w stylu **GG 6.1** dla serwera
[gg-retro](https://github.com/webs1821/gg-retro), np. `gadu-gadu.k13.pl`.

- lista kontaktów z oryginalnymi ikonkami statusu (słoneczka),
- okno rozmowy jak w GG 6: historia na żółtym tle, pasek B / I / U / kolor / emotki / obrazek, przyciski
  *Wyślij*, *↵*, *Menu*, *Wyczyść*, *Zamknij*,
- **historia zapisywana na telefonie** (nie ma osobnego archiwum),
- powiadomienia z oryginalnym dźwiękiem `wiadomosc.wav` i szybką odpowiedzią; `dostepny.wav`, gdy ktoś się pojawia,
- oryginalne emotki GG 6.1 (oba zestawy, animowane),
- wysyłanie i odbieranie obrazków (klasyczny mechanizm GG 6),
- status (Dostępny / Zaraz wracam / Niewidoczny / Niedostępny) z opisem do 70 znaków,
- import i eksport listy kontaktów z/na serwer,
- ustawienia: adres serwera i port (domyślnie `gadu-gadu.k13.pl:8074`).

## Instalacja

1. Pobierz `gg-retro-X.Y.Z.apk` z zakładki **Releases**.
2. Zainstaluj (Android 8.0+), zezwalając na instalację z nieznanych źródeł.
3. Zaloguj się numerem GG i hasłem z rejestracji na serwerze.
4. Zezwól na powiadomienia. W *Menu → Ustawienia* warto wyłączyć optymalizację baterii, wtedy połączenie
   nie jest zrywane w tle.

Port **8074** to port protokołu GG na serwerze gg-retro.

## Jak to działa

| Katalog | Zawartość |
| --- | --- |
| `gg-protocol/` | Protokół GG 6.0 w czystym Kotlinie: `GG_LOGIN60` (hash GG32), statusy, wiadomości z formatowaniem, obrazki 0x04/0x05/0x06, lista kontaktów na serwerze. Testy porównują pakiety bajt w bajt z wektorami z serwera gg-retro, a testy E2E łączą się z prawdziwym serwerem. |
| `app/` | Aplikacja: Jetpack Compose, Room (historia), DataStore (ustawienia), usługa pierwszoplanowa trzymająca połączenie. |
| `buildSrc/` | `extractGgAssets`: przy buildzie pobiera instalator `gg61.exe` z repo gg-retro (przypięty commit + SHA-256) i wypakowuje z niego emotki, dźwięki i bitmapy skórki *classic*. |
| `e2e/` | Skrypt startujący lokalny serwer gg-retro z kontami testowymi (używany w CI). |

Emoji z klawiatury telefonu (GG 6 ich nie obsługuje) są od razu zamieniane na kody emotek GG,
np. 😉 → `;)`, a pozostałe na polską nazwę w nawiasie, np. `(głowa jednorożca)`. Nazwy pochodzą z
[Unicode CLDR](https://cldr.unicode.org/) (licencja w `third_party/unicode-cldr/LICENSE`), a plik
`app/src/main/assets/emoji_pl.tsv` generuje `python3 tools/make_emoji_names.py`.

Oryginalne emotki, dźwięki i skórka GG **nie są w tym repozytorium**. Trafiają tylko do zbudowanego APK.
Ikona aplikacji powstaje z `art/gg.png`. Po podmianie tego pliku uruchom `python3 art/make_icons.py`
(wymaga Pillow).

### Ograniczenia (tak samo jak w GG 6)

- Obrazek można pobrać tylko wtedy, gdy nadawca jest połączony, bo serwer nie przechowuje obrazków.
  Niedostępny obrazek pokazuje się jako *[obrazek niedostępny – dotknij]*; dotknięcie ponawia próbę.
- Zalogowanie się tym samym numerem w innym miejscu (np. GG na Windowsie) rozłącza aplikację.
  Aplikacja nie łączy się wtedy sama ponownie, żeby nie wyrzucać drugiego klienta.
- Formatowanie (B/I/U/kolor) dotyczy całej wysyłanej wiadomości. Formatowanie przychodzące jest wyświetlane w pełni.

## Budowanie

Wymagany JDK 17 i Android SDK (API 35).

```bash
./gradlew assembleDebug
```

Bez dostępu do internetu można podać lokalną kopię instalatora:

```bash
./gradlew assembleDebug -PggInstaller=/ścieżka/do/gg61.exe
```

Testy protokołu (szybkie, bez Android SDK):

```bash
./gradlew -p gg-protocol test
```

Testy E2E z prawdziwym serwerem (potrzebny Rust, jak w `.github/workflows/android.yml`):

```bash
git clone https://github.com/webs1821/gg-retro /tmp/gg-retro
(cd /tmp/gg-retro && cargo build -p gg-server)
python3 e2e/start_gg_retro.py /tmp/gg-retro/target/debug/gg-retro /tmp/gg-e2e
```

Skrypt wypisze zmienne `GG_E2E_*`. Wyeksportuj je i uruchom `./gradlew -p gg-protocol test`.

## Wydanie (release)

Wypchnięcie tagu w formacie `X.Y.Z` (np. `1.0.0`) uruchamia `.github/workflows/release.yml`.
Workflow buduje APK i publikuje GitHub Release z plikiem `gg-retro-X.Y.Z.apk`.

Żeby kolejne wersje instalowały się jako aktualizacja, APK musi być zawsze podpisany tym samym kluczem.
Wygeneruj go raz:

```bash
keytool -genkeypair -v -keystore gg-retro.jks -alias gg-retro -keyalg RSA -keysize 2048 -validity 10000
```

Następnie dodaj sekrety repozytorium (*Settings → Secrets and variables → Actions*):

| Sekret | Wartość |
| --- | --- |
| `KEYSTORE_BASE64` | wynik `base64 -w0 gg-retro.jks` |
| `KEYSTORE_PASSWORD` | hasło keystore |
| `KEY_ALIAS` | `gg-retro` |
| `KEY_PASSWORD` | hasło klucza |

Bez tych sekretów APK zostanie podpisany kluczem debug: da się go zainstalować, ale kolejna wersja może
wymagać odinstalowania poprzedniej.
