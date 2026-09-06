/**
 * Matrix push gateway → UnifiedPush endpoint.
 *
 * Beeper's homeserver only accepts a pusher whose url sits at
 * /_matrix/push/v1/notify, and LightOS hands out endpoints under
 * .../api/webhooks/unified_push/deliver/<uuid>. This worker bridges the two:
 * the pusher points its url here and carries the LightOS endpoint as its
 * pushkey, and every notification is forwarded to that pushkey.
 *
 * The payload stays deliberately small — the tool only needs waking up, and
 * UnifiedPush caps a message at 4096 bytes.
 */

// Only ever forward to LightOS. Without this the worker is an open relay that
// anyone with a Matrix account could point at a third party.
const ALLOWED_HOSTS = ['production.lightphonecloud.com']

const MAX_BODY_BYTES = 64 * 1024
const FORWARD_TIMEOUT_MS = 10_000

export default {
  async fetch(request) {
    const url = new URL(request.url)

    if (url.pathname !== '/_matrix/push/v1/notify') {
      return json({ error: 'Not found' }, 404)
    }

    // UnifiedPush's gateway discovery: a GET here says "yes, I speak Matrix".
    if (request.method === 'GET') {
      return json({ unifiedpush: { gateway: 'matrix' } })
    }
    if (request.method !== 'POST') {
      return json({ error: 'Method not allowed' }, 405)
    }

    const raw = await request.text()
    if (raw.length > MAX_BODY_BYTES) {
      return json({ error: 'Body too large' }, 413)
    }

    let notification
    try {
      notification = JSON.parse(raw).notification
    } catch {
      return json({ error: 'Malformed JSON' }, 400)
    }
    if (!notification || !Array.isArray(notification.devices)) {
      return json({ error: 'Missing notification.devices' }, 400)
    }

    // What the tool receives. It syncs on wake, so this is a nudge, not content.
    const payload = JSON.stringify({
      event_id: notification.event_id ?? null,
      room_id: notification.room_id ?? null,
      unread: notification.counts?.unread ?? null,
    })

    const rejected = []
    for (const device of notification.devices) {
      const pushkey = device?.pushkey
      if (typeof pushkey !== 'string' || !isAllowedEndpoint(pushkey)) {
        // Telling the homeserver a pushkey is rejected makes it stop retrying it.
        if (typeof pushkey === 'string') rejected.push(pushkey)
        continue
      }
      if (!(await forward(pushkey, payload))) {
        rejected.push(pushkey)
      }
    }

    return json({ rejected })
  },
}

function isAllowedEndpoint(pushkey) {
  try {
    const endpoint = new URL(pushkey)
    return endpoint.protocol === 'https:' && ALLOWED_HOSTS.includes(endpoint.hostname)
  } catch {
    return false
  }
}

/** Returns false when the endpoint is gone and the pusher should be dropped. */
async function forward(endpoint, payload) {
  try {
    const response = await fetch(endpoint, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: payload,
      signal: AbortSignal.timeout(FORWARD_TIMEOUT_MS),
    })
    // 404/410 mean the distributor forgot this endpoint. Anything else — a
    // timeout, a 500 — is likely temporary, so keep the pusher alive.
    return response.status !== 404 && response.status !== 410
  } catch {
    return true
  }
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  })
}
