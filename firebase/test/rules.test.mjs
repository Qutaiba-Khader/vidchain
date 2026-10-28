// VidChain firestore.rules checks (owner 2026-10-28): run inside `firebase emulators:exec --only firestore`
import { initializeTestEnvironment, assertSucceeds, assertFails } from "@firebase/rules-unit-testing";
import { doc, getDoc, setDoc, deleteDoc, serverTimestamp, Timestamp, collection, getDocs } from "firebase/firestore";
import { readFileSync } from "node:fs";

const env = await initializeTestEnvironment({
  projectId: "demo-vidchain",
  firestore: { rules: readFileSync(new URL("../firestore.rules", import.meta.url), "utf8"), host: "127.0.0.1", port: 8080 },
});
const alice = env.authenticatedContext("alice", { email: "alice@example.com" }).firestore();
const bob = env.authenticatedContext("bob", { email: "bob@example.com" }).firestore();
const anon = env.unauthenticatedContext().firestore();
const good = () => ({ email: "alice@example.com", providers: ["password"], createdAt: serverTimestamp(), lastSignInAt: serverTimestamp(), appVersion: "1.2.2" });
let n = 0, bad = 0;
async function check(name, p) { n++; try { await p; console.log("ok  ", name); } catch (e) { bad++; console.log("FAIL", name, String(e).slice(0, 160)); } }

await check("owner creates a valid profile", assertSucceeds(setDoc(doc(alice, "users/alice"), good())));
await check("owner reads it", assertSucceeds(getDoc(doc(alice, "users/alice"))));
await check("owner updates lastSignInAt (merge keeps createdAt)", assertSucceeds(setDoc(doc(alice, "users/alice"), { lastSignInAt: serverTimestamp(), appVersion: "1.2.3", providers: ["password", "google.com"] }, { merge: true })));
await check("another user cannot read it", assertFails(getDoc(doc(bob, "users/alice"))));
await check("signed out cannot read it", assertFails(getDoc(doc(anon, "users/alice"))));
await check("no one can list users", assertFails(getDocs(collection(bob, "users"))));
await check("another user cannot write it", assertFails(setDoc(doc(bob, "users/alice"), good())));
await check("cannot create someone else's e-mail", assertFails(setDoc(doc(bob, "users/bob"), { ...good(), email: "alice@example.com" })));
await check("cannot add a role field", assertFails(setDoc(doc(alice, "users/alice"), { isAdmin: true, lastSignInAt: serverTimestamp() }, { merge: true })));
await check("cannot change createdAt", assertFails(setDoc(doc(alice, "users/alice"), { createdAt: Timestamp.fromMillis(0), lastSignInAt: serverTimestamp() }, { merge: true })));
await check("cannot write a client-chosen lastSignInAt", assertFails(setDoc(doc(alice, "users/alice"), { lastSignInAt: Timestamp.fromMillis(0) }, { merge: true })));
await check("cannot write a huge appVersion", assertFails(setDoc(doc(alice, "users/alice"), { appVersion: "x".repeat(5000), lastSignInAt: serverTimestamp() }, { merge: true })));
await check("cannot write an unknown provider", assertFails(setDoc(doc(alice, "users/alice"), { providers: ["evil"], lastSignInAt: serverTimestamp() }, { merge: true })));
await check("bob creates without e-mail (allowed)", assertSucceeds(setDoc(doc(bob, "users/bob"), { providers: ["google.com"], createdAt: serverTimestamp(), lastSignInAt: serverTimestamp(), appVersion: "1" })));
await check("create needs createdAt = server time", assertFails(setDoc(doc(env.authenticatedContext("carol").firestore(), "users/carol"), { providers: ["password"], createdAt: Timestamp.fromMillis(0), lastSignInAt: serverTimestamp(), appVersion: "1" })));
await check("other paths are closed", assertFails(setDoc(doc(alice, "logs/x"), { a: 1 })));
// the app's own two writes, exactly as VidChainAccount.saveProfile() sends them (non-ASCII names at the 100-char cut)
const dana = env.authenticatedContext("dana", { email: "dana@example.com" }).firestore();
const appFields = (name) => ({ email: "dana@example.com", displayName: name, providers: ["password", "google.com"], lastSignInAt: serverTimestamp(), appVersion: "1.2.3-vidchain" });
await check("app create path (set, no merge, 100 CJK chars)", assertSucceeds(setDoc(doc(dana, "users/dana"), { ...appFields("\u540d".repeat(100)), createdAt: serverTimestamp() })));
await check("app merge path (no createdAt, 100 UTF-16 units of emoji)", assertSucceeds(setDoc(doc(dana, "users/dana"), appFields("\u{1F600}".repeat(50)), { merge: true })));
await check("cannot write a 101-char displayName", assertFails(setDoc(doc(dana, "users/dana"), { displayName: "x".repeat(101), lastSignInAt: serverTimestamp() }, { merge: true })));
await check("cannot write duplicate providers", assertFails(setDoc(doc(dana, "users/dana"), { providers: ["password", "password"], lastSignInAt: serverTimestamp() }, { merge: true })));
await check("cannot write three providers", assertFails(setDoc(doc(dana, "users/dana"), { providers: ["password", "google.com", "password"], lastSignInAt: serverTimestamp() }, { merge: true })));
await check("cannot write empty providers", assertFails(setDoc(doc(dana, "users/dana"), { providers: [], lastSignInAt: serverTimestamp() }, { merge: true })));
await check("owner cannot delete (would reset createdAt on re-create)", assertFails(deleteDoc(doc(alice, "users/alice"))));
await check("another user cannot delete it", assertFails(deleteDoc(doc(bob, "users/alice"))));
await env.cleanup();
console.log(`${n - bad}/${n} rule checks passed`);
process.exit(bad ? 1 : 0);
