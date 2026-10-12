// Google AdMob SSV endpoint. Deploy with JWT verification disabled ONLY for this signed webhook.
const EXPECTED_AD_UNIT = "ca-app-pub-8854680295966508/3570380150";
const GOOGLE_KEYS = "https://www.gstatic.com/admob/reward/verifier-keys.json";
function decode64(v: string): Uint8Array {
  const s = v.replace(/-/g, "+").replace(/_/g, "/");
  return Uint8Array.from(atob(s + "=".repeat((4 - s.length % 4) % 4)), c => c.charCodeAt(0));
}
function derToRaw(s: Uint8Array): Uint8Array {
  let i = 0;
  if (s[i++] !== 0x30) throw Error("Invalid DER");
  let length = s[i++];
  if (length & 0x80) {
    const bytes = length & 0x7f;
    if (bytes < 1 || bytes > 2) throw Error("Invalid DER length");
    length = 0;
    for (let j = 0; j < bytes; j++) length = (length << 8) | s[i++];
  }
  if (i + length !== s.length) throw Error("Invalid signature size");
  const result = new Uint8Array(64);
  for (let part = 0; part < 2; part++) {
    if (s[i++] !== 0x02) throw Error("Invalid DER integer");
    const n = s[i++];
    if (n < 1 || n > 33 || i + n > s.length) throw Error("Invalid DER integer length");
    let start = i, count = n;
    while (count > 1 && s[start] === 0) { start++; count--; }
    if (count > 32) throw Error("Invalid ECDSA integer");
    result.set(s.slice(start, start + count), part * 32 + 32 - count);
    i += n;
  }
  if (i !== s.length) throw Error("Trailing DER data");
  return result;
}
Deno.serve(async (req: Request) => {
  if (req.method !== "GET") return new Response("Method not allowed", { status: 405 });
  try {
    const raw = new URL(req.url).search.slice(1);
    const marker = "&signature=";
    const cut = raw.lastIndexOf(marker);
    if (cut < 0) {
      const probe = new URLSearchParams(raw);
      if (!probe.has("transaction_id") && !probe.has("signature") && !probe.has("key_id"))
        return new Response("OK", { status: 200 });
      return new Response("Unsigned", { status: 400 });
    }
    const signed = raw.slice(0, cut);
    const tail = raw.slice(cut + 1);
    if (!/^signature=[^&]+&key_id=[0-9]+$/.test(tail)) return new Response("Bad signature parameters", { status: 400 });
    const sigFields = new URLSearchParams(tail);
    const params = new URLSearchParams(signed);
    for (const k of ["ad_unit", "reward_amount", "reward_item", "timestamp", "transaction_id"]) {
      if (params.getAll(k).length !== 1) return new Response("Missing or duplicate field", { status: 400 });
    }
    if (params.get("ad_unit") !== EXPECTED_AD_UNIT || params.get("reward_amount") !== "10" || params.get("reward_item") !== "BoostCoins")
      return new Response("Unexpected reward", { status: 400 });
    const transactionId = params.get("transaction_id")!;
    if (transactionId.length < 8 || transactionId.length > 256) return new Response("Invalid transaction", { status: 400 });
    const ts = Number(params.get("timestamp"));
    if (!Number.isSafeInteger(ts) || Math.abs(Date.now() - ts) > 24 * 60 * 60 * 1000)
      return new Response("Expired callback", { status: 400 });
    const keysRes = await fetch(GOOGLE_KEYS);
    if (!keysRes.ok) throw Error("Google keys unavailable");
    const keys = await keysRes.json();
    const key = (keys.keys as Array<{keyId: number; pem: string}>).find(k => String(k.keyId) === sigFields.get("key_id"));
    if (!key) return new Response("Unknown key", { status: 403 });
    const pem = key.pem.replace(/-----BEGIN PUBLIC KEY-----|-----END PUBLIC KEY-----|\s/g, "");
    const cryptoKey = await crypto.subtle.importKey("spki", decode64(pem), { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
    const ok = await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, cryptoKey, derToRaw(decode64(sigFields.get("signature")!)), new TextEncoder().encode(signed));
    if (!ok) return new Response("Invalid signature", { status: 403 });
    // La prueba firmada de AdMob puede no incluir un usuario.
    // Nunca se conceden monedas sin un identificador válido.
    if (!params.has("custom_data")) return new Response("OK", { status: 200 });
    if (params.getAll("custom_data").length !== 1)
      return new Response("Invalid user", { status: 400 });
    const userId = params.get("custom_data")!;
    if (!/^[a-f0-9]{8}-[a-f0-9]{4}-[1-8][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/i.test(userId))
      return new Response("Invalid user", { status: 400 });
    const base = Deno.env.get("SUPABASE_URL");
    const secret = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
    if (!base || !secret) throw Error("Missing server credentials");
    const res = await fetch(`${base}/rest/v1/rpc/credit_verified_ad`, {
      method: "POST",
      headers: { "apikey": secret, "Authorization": `Bearer ${secret}`, "Content-Type": "application/json" },
      body: JSON.stringify({ p_user_id: userId, p_transaction_id: transactionId }),
    });
    if (!res.ok) throw Error(`Reward RPC failed: ${res.status}`);
    return new Response("OK", { status: 200 });
  } catch (e) {
    console.error("AdMob SSV error", e);
    return new Response("Retry later", { status: 503 });
  }
});
