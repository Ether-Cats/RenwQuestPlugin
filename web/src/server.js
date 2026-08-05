import { createApp } from "./app.js";
import { loadConfig } from "./config.js";
import { createDatabase } from "./database.js";
import { hashPassword, validatePassword, validateUsername, verifyPassword } from "./auth.js";

const config = loadConfig();
const database = createDatabase(config);

await database.migrate();
const adminUsername = validateUsername(config.adminUsername);
validatePassword(config.adminPassword);
const existingAdmin = await database.findUser(adminUsername);
let adminCreated = false;
if (!existingAdmin) {
  adminCreated = await database.ensureAdminUser(adminUsername, hashPassword(config.adminPassword));
} else if (!verifyPassword(config.adminPassword, existingAdmin.password_hash)) {
  await database.updateUserPassword(existingAdmin.id, hashPassword(config.adminPassword));
  console.log(`siyuan Web admin password rotated: ${adminUsername}`);
}
if (adminCreated) console.log(`siyuan Web admin account initialized: ${adminUsername}`);
await database.purgeSessions();
const app = createApp(config, database);
const server = app.listen(config.port, "0.0.0.0", () => {
  console.log(`siyuan Menu Web listening on port ${config.port}`);
});

async function shutdown(signal) {
  console.log(`${signal} received, shutting down`);
  server.close(async () => {
    await database.close();
    process.exit(0);
  });
  setTimeout(() => process.exit(1), 10_000).unref();
}

process.on("SIGTERM", () => shutdown("SIGTERM"));
process.on("SIGINT", () => shutdown("SIGINT"));
