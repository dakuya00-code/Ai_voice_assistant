# Telegram Integration

- Chat ID: `8826323760`
- Bot username: `@OngHermes_AI_bot`
- Delivery target: Telegram 안내 및 APK 전달

## GitHub Actions secrets
- `TELEGRAM_BOT_TOKEN`
- `TELEGRAM_CHAT_ID`

## Notes
- Bot token is intentionally not stored here.
- Use the Telegram Bot API with the chat ID above for sending APKs/alerts.
- The GitHub Actions workflow uploads the APK artifact and then sends it via Telegram when the secrets are configured.
