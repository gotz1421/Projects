// Cuentas y datos en la nube (Firebase Authentication + Cloud Firestore).
// Cada usuario tiene su propio espacio: users/{uid} (ajustes, catálogos, borrador)
// y users/{uid}/quotes/{id} (cotizaciones). Las reglas de firestore.rules impiden
// que un usuario lea o escriba los datos de otro.
import { initializeApp } from 'https://www.gstatic.com/firebasejs/12.19.0/firebase-app.js';
import {
  getAuth, onAuthStateChanged, signInWithEmailAndPassword, createUserWithEmailAndPassword,
  sendPasswordResetEmail, signOut, connectAuthEmulator,
} from 'https://www.gstatic.com/firebasejs/12.19.0/firebase-auth.js';
import {
  initializeFirestore, persistentLocalCache, persistentMultipleTabManager, connectFirestoreEmulator,
  doc, getDoc, setDoc, deleteDoc, collection, getDocs, writeBatch,
} from 'https://www.gstatic.com/firebasejs/12.19.0/firebase-firestore.js';
import { firebaseConfig, emulators } from './firebase-config.js';

export const configured = Boolean(firebaseConfig?.apiKey && !firebaseConfig.apiKey.startsWith('PEGA_'));

let auth = null;
let fs = null;
if (configured) {
  const app = initializeApp(firebaseConfig);
  auth = getAuth(app);
  auth.languageCode = 'es';
  fs = initializeFirestore(app, {
    ignoreUndefinedProperties: true,
    // Caché local: la app abre rápido y aguanta cortes breves de internet
    localCache: emulators ? undefined : persistentLocalCache({ tabManager: persistentMultipleTabManager() }),
  });
  if (emulators) {
    connectAuthEmulator(auth, `http://${emulators.auth}`, { disableWarnings: true });
    const [host, port] = emulators.firestore.split(':');
    connectFirestoreEmulator(fs, host, Number(port));
  }
}

export const currentUser = () => auth?.currentUser || null;
export const onUser = (cb) => (auth ? onAuthStateChanged(auth, cb) : cb(null));
export const signIn = (email, pass) => signInWithEmailAndPassword(auth, email, pass);
export const signUp = (email, pass) => createUserWithEmailAndPassword(auth, email, pass);
export const resetPassword = (email) => sendPasswordResetEmail(auth, email);
export const logOut = async () => { await flush(); await signOut(auth); };

const uid = () => auth.currentUser.uid;
const mainRef = () => doc(fs, 'users', uid());
const quotesCol = () => collection(fs, 'users', uid(), 'quotes');

// Devuelve { main, quotes } o { main: null } si la cuenta es nueva.
export async function loadAll() {
  const snap = await getDoc(mainRef());
  if (!snap.exists()) return { main: null, quotes: [] };
  const qs = await getDocs(quotesCol());
  const quotes = qs.docs.map((d) => d.data()).sort((a, b) => b.id - a.id);
  return { main: snap.data(), quotes };
}

// Guardado del documento principal con espera corta para no escribir en cada tecla.
let pending = null;
let timer = null;
let lastWrite = Promise.resolve();
export function saveMain(data) {
  pending = data;
  clearTimeout(timer);
  timer = setTimeout(flush, 700);
}
export function flush() {
  clearTimeout(timer);
  if (pending && auth?.currentUser) {
    const data = JSON.parse(JSON.stringify(pending));
    pending = null;
    lastWrite = setDoc(mainRef(), data).catch((e) => { console.error(e); throw e; });
  }
  return lastWrite;
}
addEventListener('pagehide', () => { flush(); });
document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden') flush(); });

export const putQuote = (q) => setDoc(doc(quotesCol(), String(q.id)), JSON.parse(JSON.stringify(q)));
export const removeQuote = (id) => deleteDoc(doc(quotesCol(), String(id)));

// Reemplaza todos los datos de la cuenta (restaurar respaldo / importar datos locales).
export async function replaceAll(main, quotes) {
  const old = await getDocs(quotesCol());
  const keep = new Set(quotes.map((q) => String(q.id)));
  const ops = [
    ...old.docs.filter((d) => !keep.has(d.id)).map((d) => (b) => b.delete(d.ref)),
    (b) => b.set(mainRef(), JSON.parse(JSON.stringify(main))),
    ...quotes.map((q) => (b) => b.set(doc(quotesCol(), String(q.id)), JSON.parse(JSON.stringify(q)))),
  ];
  // Firestore acepta hasta 500 operaciones por lote
  for (let i = 0; i < ops.length; i += 450) {
    const batch = writeBatch(fs);
    ops.slice(i, i + 450).forEach((op) => op(batch));
    await batch.commit();
  }
  pending = null;
}

export function errorText(e) {
  const map = {
    'auth/invalid-email': 'El correo no es válido.',
    'auth/missing-password': 'Escribe tu contraseña.',
    'auth/weak-password': 'La contraseña debe tener al menos 6 caracteres.',
    'auth/email-already-in-use': 'Ya existe una cuenta con ese correo. Entra con tu contraseña.',
    'auth/invalid-credential': 'Correo o contraseña incorrectos.',
    'auth/wrong-password': 'Correo o contraseña incorrectos.',
    'auth/user-not-found': 'Correo o contraseña incorrectos.',
    'auth/too-many-requests': 'Demasiados intentos. Espera unos minutos.',
    'auth/network-request-failed': 'Sin conexión a internet.',
    'auth/operation-not-allowed': 'Activa “Correo/contraseña” en Firebase Authentication.',
    'permission-denied': 'Sin permiso: revisa las reglas de Firestore.',
  };
  return map[e?.code] || 'Ocurrió un error. Intenta de nuevo.';
}
