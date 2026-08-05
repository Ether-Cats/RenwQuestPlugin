import crypto from "node:crypto";

const SCRYPT_N = 32_768;
const SCRYPT_R = 8;
const SCRYPT_P = 1;
const SCRYPT_KEY_LENGTH = 64;

export function validateUsername(value) {
  const username = String(value || "").trim();
  if (!/^[A-Za-z0-9_.@-]{3,64}$/.test(username)) throw new Error("账号必须是 3 到 64 位字母、数字或 ._-@ 字符");
  return username;
}

export function validatePassword(value) {
  const password = String(value || "");
  if (password.length < 12 || password.length > 256) throw new Error("密码长度必须为 12 到 256 位");
  return password;
}

export function hashPassword(password) {
  const safePassword = validatePassword(password);
  const salt = crypto.randomBytes(16);
  const hash = crypto.scryptSync(safePassword, salt, SCRYPT_KEY_LENGTH, {
    N: SCRYPT_N,
    r: SCRYPT_R,
    p: SCRYPT_P,
    maxmem: 64 * 1024 * 1024
  });
  return ["scrypt", SCRYPT_N, SCRYPT_R, SCRYPT_P, salt.toString("base64url"), hash.toString("base64url")].join("$");
}

export function verifyPassword(password, encoded) {
  try {
    const [algorithm, n, r, p, saltText, hashText] = String(encoded || "").split("$");
    if (algorithm !== "scrypt") return false;
    const salt = Buffer.from(saltText, "base64url");
    const expected = Buffer.from(hashText, "base64url");
    const actual = crypto.scryptSync(String(password || ""), salt, expected.length, {
      N: Number(n),
      r: Number(r),
      p: Number(p),
      maxmem: 64 * 1024 * 1024
    });
    return actual.length === expected.length && crypto.timingSafeEqual(actual, expected);
  } catch {
    return false;
  }
}

export function randomToken() {
  return crypto.randomBytes(32).toString("base64url");
}

export function hashToken(token) {
  return crypto.createHash("sha256").update(String(token || "")).digest("hex");
}

export function readCookie(request, name) {
  const header = request.get("cookie") || "";
  for (const part of header.split(";")) {
    const separator = part.indexOf("=");
    if (separator < 0 || part.slice(0, separator).trim() !== name) continue;
    try { return decodeURIComponent(part.slice(separator + 1).trim()); }
    catch { return ""; }
  }
  return "";
}

export function setSessionCookie(response, token, maxAgeSeconds, secure) {
  const attributes = ["Path=/", "HttpOnly", "SameSite=Lax", `Max-Age=${Math.max(0, Math.floor(maxAgeSeconds))}`];
  if (secure) attributes.push("Secure");
  response.setHeader("Set-Cookie", `siyuan_session=${encodeURIComponent(token)}; ${attributes.join("; ")}`);
}

export function clearSessionCookie(response, secure) {
  setSessionCookie(response, "", 0, secure);
}
