# Matrix push gateway for the Light Phone 3

Beeper's homeserver rejects a pusher unless its `url` ends in
`/_matrix/push/v1/notify`:

```
statusCode=400 Config Error: 'url' must have a path of '/_matrix/push/v1/notify'
```

LightOS hands out UnifiedPush endpoints under
`https://production.lightphonecloud.com/api/webhooks/unified_push/deliver/<uuid>`,
and Light [declined to serve the Matrix path themselves][168]. This worker sits
in between: the pusher's `url` points here, its `pushkey` stays the LightOS
endpoint, and every notification is forwarded to that endpoint.

[168]: https://github.com/orgs/lightphone/discussions/168

## Deploy

```bash
cd push-gateway
npx wrangler deploy
```

Wrangler prints the URL it deployed to, something like
`https://beeper-lp3-push-gateway.<subdomain>.workers.dev`. Put that in
`PUSH_GATEWAY_URL` in `BeeperRepository.kt`, rebuild, and reinstall — the app
registers the pusher with it on the next start.

## Check it works

```bash
curl https://<your-worker>.workers.dev/_matrix/push/v1/notify
```

should answer `{"unifiedpush":{"gateway":"matrix"}}`, and after the app has
registered:

```bash
adb logcat -d | grep -i pusher
```

should say `Successfully registered pusher`. Send yourself a message and the
tool should wake and sync.

## What it will and won't do

- Forwards only to `production.lightphonecloud.com`. Without that check anyone
  with a Matrix account could point a pusher here and use it as a relay.
- Sends event id, room id and the unread count — the tool syncs on wake, so
  there is no message content in flight, and UnifiedPush caps a message at
  4096 bytes anyway.
- Reports a pushkey as `rejected` when the endpoint answers 404 or 410, so the
  homeserver stops pushing to an endpoint LightOS has forgotten.
