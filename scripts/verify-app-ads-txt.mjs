import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const expected = "google.com, pub-4783826505860771, DIRECT, f08c47fec0942fa0\n";
const files = ["public/app-ads.txt", "dist/app-ads.txt"];
let failed = false;

for (const relativePath of files) {
  const fullPath = resolve(relativePath);
  let actual;
  try {
    actual = readFileSync(fullPath, "utf8");
  } catch (error) {
    console.error(`FAIL ${relativePath}: ${error.message}`);
    failed = true;
    continue;
  }
  if (actual !== expected) {
    console.error(`FAIL ${relativePath}: content must match the required single record and final newline exactly.`);
    failed = true;
  } else {
    console.log(`PASS ${relativePath}: exact AdMob record verified.`);
  }
}
if (failed) process.exit(1);
