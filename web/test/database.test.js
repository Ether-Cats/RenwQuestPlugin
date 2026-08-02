import assert from "node:assert/strict";
import test from "node:test";
import { adaptMysqlQuery } from "../src/database.js";

test("MySQL adapter preserves positional parameter ordering and JSON documents", () => {
  const document = { title: "&6Main", size: 9, items: [] };
  const result = adaptMysqlQuery(
    "INSERT INTO web_menu_versions(id, menu_id, document, created_by) VALUES ($2, $1, $3, $1)",
    ["editor", "version-id", document]
  );

  assert.equal(result.sql, "INSERT INTO web_menu_versions(id, menu_id, document, created_by) VALUES (?, ?, ?, ?)");
  assert.deepEqual(result.values, ["version-id", "editor", JSON.stringify(document), "editor"]);
});
