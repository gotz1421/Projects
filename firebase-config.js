// Pega aquí la configuración de tu app web de Firebase:
// Consola de Firebase → ⚙ Configuración del proyecto → General → Tus apps → App web → "Configuración".
// Estos datos no son secretos: la seguridad la dan las reglas de firestore.rules.
export const firebaseConfig = {
  apiKey: 'PEGA_AQUI_TU_API_KEY',
  authDomain: 'TU-PROYECTO.firebaseapp.com',
  projectId: 'TU-PROYECTO',
  storageBucket: 'TU-PROYECTO.firebasestorage.app',
  messagingSenderId: '',
  appId: '',
};

// Solo para pruebas locales con Firebase Emulator Suite; en producción déjalo en null.
export const emulators = null;
