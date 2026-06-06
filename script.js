/**
 * ═══════════════════════════════════════════════════════════════
 *  YouFree – script.js
 *  Plataforma de organização de playlists e download de mídia
 *
 *  Arquitetura: SPA em Vanilla JS ES6+
 *  Persistência: LocalStorage (GitHub Pages)
 *  Pronto para migração ao Back-end Base44
 * ═══════════════════════════════════════════════════════════════
 */

'use strict';

/* ╔════════════════════════════════════════════════════════════╗
   ║  1. CONSTANTES E BANCO DE DADOS SIMULADO                  ║
   ╚════════════════════════════════════════════════════════════╝ */

/** Chaves de LocalStorage */
const LS = {
  USERS:     'yf_users',
  SESSION:   'yf_session',
  PLAYLISTS: 'yf_playlists',
};

/**
 * Base de dados de álbuns simulados.
 * Dividida em grupos de 8; cada hora exibe um grupo diferente.
 * Imagens: Unsplash (sem chave de API necessária).
 */
const ALBUM_BANK = [
  // Grupo 0 – hora par inicial
  [
    { title: 'After Hours', artist: 'The Weeknd', cover: 'https://images.unsplash.com/photo-1493225457124-a3eb161ffa5f?w=300&h=300&fit=crop', url: 'https://youtube.com/watch?v=X' },
    { title: 'Future Nostalgia', artist: 'Dua Lipa', cover: 'https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=300&h=300&fit=crop', url: 'https://youtube.com/watch?v=Y' },
    { title: 'DAMN.', artist: 'Kendrick Lamar', cover: 'https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=300&h=300&fit=crop', url: 'https://youtube.com/watch?v=Z' },
    { title: '÷ (Divide)', artist: 'Ed Sheeran', cover: 'https://images.unsplash.com/photo-1470225620780-dba8ba36b745?w=300&h=300&fit=crop', url: 'https://youtube.com/watch?v=A' },
    { title: 'Blonde', artist: 'Frank Ocean', cover: 'https://images.unsplash.com/photo-1487180144351-b8472da7d491?w=300&h=300&fit=crop', url: 'https://youtube.com/watch?v=B' },
    { title: 'Midnights', artist: 'Taylor Swift', cover: 'https://images.unsplash.com/photo-1501386761578-eac5c94b800a?w=300&h=300&fit=crop', url: 'https://youtube.com/watch?v=C' },
    { title: 'Random Access Memories', artist: 'Daft Punk', cover: 'https://images.unsplash.com/photo-1516450360452-9312f5e86fc7?w=300&h=300&fit=crop', url: 'https://youtube.com/watch?v=D' },
    { title: 'good kid, m.A.A.d city', artist: 'Kendrick Lamar', cover: 'https://images.unsplash.com/photo-1478147427282-58a87a433b70?w=300&h=300&fit=crop', url: 'https://youtube.com/watch?v=E' },
  ],
  // Grupo 1
  [
    { title: 'Thriller', artist: 'Michael Jackson', cover: 'https://images.unsplash.com/photo-1614680376739-414d95ff43df?w=300&h=300&fit=crop', url: 'https://youtube.com/watch?v=F' },
    { title: '1989', artist: 'Taylor Swift', cover: 'https://images.unsplash.com/photo-1464375117522-1311d6a5b81f?w=300&h=300&fit=crop', url: 'https://youtube.com/watch?v=G' },
    { title: 'Lemonade', artist: 'Beyoncé', cover: 'https://images.unsplash.com/photo-1506157786151-b8491531f063?w=300&h=300&fit=crop', url: 'https://youtube.com/watch?v=H' },
    { title: 'In Rainbows', artist: 'Radiohead', cover: 'https://images.unsplash.com/photo-1519925610903-381054cc2a1c?w=300&h=300&fit=crop', url: 'https://youtube.com/watch?v=I' },
    { title: 'folklore', artist: 'Taylor Swift', cover: 'https://images.unsplash.com/photo-1468581264429-2548ef9eb732?w=300&h=300&fit=crop', url: 'https://youtube.com/watch?v=J' },
    { title: 'DS2', artist: 'Future', cover: 'https://images.unsplash.com/photo-1571330735066-03aaa9429d89?w=300&h=300&fit=crop', url: 'https://youtube.com/watch?v=K' },
    { title: 'Igor', artist: 'Tyler, the Creator', cover: 'https://images.unsplash.com/photo-1459749411175-04bf5292ceea?w=300&h=300&fit=crop', url: 'https://youtube.com/watch?v=L' },
    { title: '25', artist: 'Adele', cover: 'https://images.unsplash.com/photo-1508700115892-45ecd05ae2ad?w=300&h=300&fit=crop', url: 'https://youtube.com/watch?v=M' },
  ],
  // Grupo 2
  [
    { title: 'The Dark Side of the Moon', artist: 'Pink Floyd', cover: 'https://images.unsplash.com/photo-1614680376573-df3480f0c6ff?w=300&h=300&fit=crop', url: 'https://youtube.com/watch?v=N' },
    { title: 'Back in Black', artist: 'AC/DC', cover: 'https://images.unsplash.com/photo-1498038432885-c6f3f1b912ee?w=300&h=300&fit=crop', url: 'https://youtube.com/watch?v=O' },
    { title: 'Nevermind', artist: 'Nirvana', cover: 'https://images.unsplash.com/photo-1510915361894-db8b60106cb1?w=300&h=300&fit=crop', url: 'https://youtube.com/watch?v=P' },
    { title: 'Abbey Road', artist: 'The Beatles', cover: 'https://images.unsplash.com/photo-1493225457124-a3eb161ffa5f?w=300&h=300&fit=crop', url: 'https://youtube.com/watch?v=Q' },
    { title: 'Songs of Experience', artist: 'U2', cover: 'https://images.unsplash.com/photo-1485579149621-3123dd979885?w=300&h=300&fit=crop', url: 'https://youtube.com/watch?v=R' },
    { title: 'Swimming', artist: 'Mac Miller', cover: 'https://images.unsplash.com/photo-1501612780327-45045538702b?w=300&h=300&fit=crop', url: 'https://youtube.com/watch?v=S' },
    { title: 'Scorpion', artist: 'Drake', cover: 'https://images.unsplash.com/photo-1504898770365-14faca6a7320?w=300&h=300&fit=crop', url: 'https://youtube.com/watch?v=T' },
    { title: 'Starboy', artist: 'The Weeknd', cover: 'https://images.unsplash.com/photo-1508854710579-5cecc3a9ff17?w=300&h=300&fit=crop', url: 'https://youtube.com/watch?v=U' },
  ],
];

/** Base de dados de artistas simulados */
const ARTIST_BANK = [
  [
    { name: 'The Weeknd', genre: 'R&B / Pop', avatar: 'https://images.unsplash.com/photo-1493225457124-a3eb161ffa5f?w=200&h=200&fit=crop&crop=face' },
    { name: 'Dua Lipa', genre: 'Dance Pop', avatar: 'https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=200&h=200&fit=crop&crop=face' },
    { name: 'Kendrick Lamar', genre: 'Hip-Hop', avatar: 'https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=200&h=200&fit=crop&crop=face' },
    { name: 'Taylor Swift', genre: 'Pop / Country', avatar: 'https://images.unsplash.com/photo-1470225620780-dba8ba36b745?w=200&h=200&fit=crop&crop=face' },
    { name: 'Ed Sheeran', genre: 'Pop / Folk', avatar: 'https://images.unsplash.com/photo-1487180144351-b8472da7d491?w=200&h=200&fit=crop&crop=face' },
    { name: 'Beyoncé', genre: 'R&B / Pop', avatar: 'https://images.unsplash.com/photo-1506157786151-b8491531f063?w=200&h=200&fit=crop&crop=face' },
  ],
  [
    { name: 'Drake', genre: 'Hip-Hop / Rap', avatar: 'https://images.unsplash.com/photo-1504898770365-14faca6a7320?w=200&h=200&fit=crop&crop=face' },
    { name: 'Adele', genre: 'Soul / Pop', avatar: 'https://images.unsplash.com/photo-1508700115892-45ecd05ae2ad?w=200&h=200&fit=crop&crop=face' },
    { name: 'Frank Ocean', genre: 'R&B / Soul', avatar: 'https://images.unsplash.com/photo-1501386761578-eac5c94b800a?w=200&h=200&fit=crop&crop=face' },
    { name: 'Daft Punk', genre: 'Electronic', avatar: 'https://images.unsplash.com/photo-1516450360452-9312f5e86fc7?w=200&h=200&fit=crop&crop=face' },
    { name: 'Radiohead', genre: 'Alternative', avatar: 'https://images.unsplash.com/photo-1519925610903-381054cc2a1c?w=200&h=200&fit=crop&crop=face' },
    { name: 'Mac Miller', genre: 'Hip-Hop', avatar: 'https://images.unsplash.com/photo-1501612780327-45045538702b?w=200&h=200&fit=crop&crop=face' },
  ],
  [
    { name: 'Tyler, the Creator', genre: 'Hip-Hop', avatar: 'https://images.unsplash.com/photo-1459749411175-04bf5292ceea?w=200&h=200&fit=crop&crop=face' },
    { name: 'Pink Floyd', genre: 'Progressive Rock', avatar: 'https://images.unsplash.com/photo-1614680376573-df3480f0c6ff?w=200&h=200&fit=crop&crop=face' },
    { name: 'Nirvana', genre: 'Grunge / Rock', avatar: 'https://images.unsplash.com/photo-1498038432885-c6f3f1b912ee?w=200&h=200&fit=crop&crop=face' },
    { name: 'The Beatles', genre: 'Rock / Pop', avatar: 'https://images.unsplash.com/photo-1478147427282-58a87a433b70?w=200&h=200&fit=crop&crop=face' },
    { name: 'AC/DC', genre: 'Hard Rock', avatar: 'https://images.unsplash.com/photo-1510915361894-db8b60106cb1?w=200&h=200&fit=crop&crop=face' },
    { name: 'Future', genre: 'Trap / Rap', avatar: 'https://images.unsplash.com/photo-1571330735066-03aaa9429d89?w=200&h=200&fit=crop&crop=face' },
  ],
];

/**
 * Dados simulados de vídeos — usados para simular a resposta de API ao processar um link.
 * Na Base44, essa função será substituída por chamada real de API.
 */
const MOCK_VIDEOS = [
  { title: 'Blinding Lights', artist: 'The Weeknd', duration: '3:20', cover: 'https://images.unsplash.com/photo-1493225457124-a3eb161ffa5f?w=400&h=400&fit=crop' },
  { title: 'Levitating', artist: 'Dua Lipa', duration: '3:23', cover: 'https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=400&h=400&fit=crop' },
  { title: 'HUMBLE.', artist: 'Kendrick Lamar', duration: '2:57', cover: 'https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=400&h=400&fit=crop' },
  { title: 'Shape of You', artist: 'Ed Sheeran', duration: '3:53', cover: 'https://images.unsplash.com/photo-1470225620780-dba8ba36b745?w=400&h=400&fit=crop' },
  { title: 'Nights', artist: 'Frank Ocean', duration: '5:07', cover: 'https://images.unsplash.com/photo-1487180144351-b8472da7d491?w=400&h=400&fit=crop' },
  { title: 'Anti-Hero', artist: 'Taylor Swift', duration: '3:20', cover: 'https://images.unsplash.com/photo-1501386761578-eac5c94b800a?w=400&h=400&fit=crop' },
  { title: 'Get Lucky', artist: 'Daft Punk ft. Pharrell', duration: '6:09', cover: 'https://images.unsplash.com/photo-1516450360452-9312f5e86fc7?w=400&h=400&fit=crop' },
  { title: 'Creep', artist: 'Radiohead', duration: '3:58', cover: 'https://images.unsplash.com/photo-1519925610903-381054cc2a1c?w=400&h=400&fit=crop' },
];

/* ╔════════════════════════════════════════════════════════════╗
   ║  CATÁLOGO DE ARTISTAS – Busca Rápida por Artista          ║
   ╚════════════════════════════════════════════════════════════╝ */

/**
 * Banco de dados simulado de artistas com suas top 5 músicas.
 * Cada música tem um link real/demonstrativo do YouTube.
 */
const ARTIST_CATALOG = [
  {
    name: 'Coldplay',
    genre: 'Alternative Rock / Pop',
    photo: 'https://images.unsplash.com/photo-1470225620780-dba8ba36b745?w=400&h=400&fit=crop',
    songs: [
      { title: 'The Scientist', url: 'https://www.youtube.com/watch?v=RB-RcX5DS5A' },
      { title: 'Yellow', url: 'https://www.youtube.com/watch?v=yKNxeF4KMsY' },
      { title: 'Fix You', url: 'https://www.youtube.com/watch?v=k4V3Mo61fJM' },
      { title: 'A Sky Full of Stars', url: 'https://www.youtube.com/watch?v=VPRjCeoBqrI' },
      { title: 'Viva la Vida', url: 'https://www.youtube.com/watch?v=dvgZkm1xWPE' },
    ],
  },
  {
    name: 'Ed Sheeran',
    genre: 'Pop / Folk',
    photo: 'https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=400&h=400&fit=crop',
    songs: [
      { title: 'Shape of You', url: 'https://www.youtube.com/watch?v=JGwWNGJdvx8' },
      { title: 'Perfect', url: 'https://www.youtube.com/watch?v=2Vv-BfVoq4g' },
      { title: 'Thinking Out Loud', url: 'https://www.youtube.com/watch?v=lp-EO5I60KA' },
      { title: 'Photograph', url: 'https://www.youtube.com/watch?v=nSDgHBxUbVQ' },
      { title: 'Bad Habits', url: 'https://www.youtube.com/watch?v=orJSJGHjBLI' },
    ],
  },
  {
    name: 'Taylor Swift',
    genre: 'Pop / Country',
    photo: 'https://images.unsplash.com/photo-1501386761578-eac5c94b800a?w=400&h=400&fit=crop',
    songs: [
      { title: 'Anti-Hero', url: 'https://www.youtube.com/watch?v=b1kbLwvqugk' },
      { title: 'Shake It Off', url: 'https://www.youtube.com/watch?v=nfWlot6h_JM' },
      { title: 'Blank Space', url: 'https://www.youtube.com/watch?v=e-ORhEE9VVg' },
      { title: 'Love Story', url: 'https://www.youtube.com/watch?v=8xg3vE8Ie_E' },
      { title: 'cardigan', url: 'https://www.youtube.com/watch?v=K-a8s8OLBSE' },
    ],
  },
  {
    name: 'Anitta',
    genre: 'Pop / Funk / Reggaeton',
    photo: 'https://images.unsplash.com/photo-1516450360452-9312f5e86fc7?w=400&h=400&fit=crop',
    songs: [
      { title: 'Envolver', url: 'https://www.youtube.com/watch?v=SByXoS8ryb4' },
      { title: 'Girl From Rio', url: 'https://www.youtube.com/watch?v=MlxpGEBqLCU' },
      { title: 'Funk Rave', url: 'https://www.youtube.com/watch?v=sn7H9CRsqfM' },
      { title: 'Paradinha', url: 'https://www.youtube.com/watch?v=mXnJiYSIv8c' },
      { title: 'Vai Malandra', url: 'https://www.youtube.com/watch?v=gpuNTdHayqo' },
    ],
  },
  {
    name: 'Alok',
    genre: 'Electronic / House',
    photo: 'https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=400&h=400&fit=crop',
    songs: [
      { title: 'Hear Me Now', url: 'https://www.youtube.com/watch?v=2MFGqoKd578' },
      { title: 'Never Let Me Go', url: 'https://www.youtube.com/watch?v=Gb_8Ej7Gg5c' },
      { title: 'In My Arms', url: 'https://www.youtube.com/watch?v=8K-YjTVFo4A' },
      { title: 'Don\'t Say Goodbye', url: 'https://www.youtube.com/watch?v=1gJ6MJOIBAQ' },
      { title: 'Big Jet Plane', url: 'https://www.youtube.com/watch?v=J0Cb4xGhFzI' },
    ],
  },
];


/* ╔════════════════════════════════════════════════════════════╗
   ║  TOP 10 – BOMBANDO NO YOUFREE                             ║
   ╚════════════════════════════════════════════════════════════╝ */

/**
 * Top 10 músicas mais populares simuladas.
 * Cada entrada tem título, artista, URL do YouTube e ID para preview.
 */
const TOP10_TRACKS = [
  { rank: 1,  title: 'Blinding Lights',    artist: 'The Weeknd',       url: 'https://www.youtube.com/watch?v=4NRXx6U8ABQ', ytId: '4NRXx6U8ABQ', plays: '2.4M' },
  { rank: 2,  title: 'Shape of You',       artist: 'Ed Sheeran',       url: 'https://www.youtube.com/watch?v=JGwWNGJdvx8', ytId: 'JGwWNGJdvx8', plays: '2.1M' },
  { rank: 3,  title: 'Envolver',           artist: 'Anitta',           url: 'https://www.youtube.com/watch?v=SByXoS8ryb4', ytId: 'SByXoS8ryb4', plays: '1.9M' },
  { rank: 4,  title: 'Anti-Hero',          artist: 'Taylor Swift',     url: 'https://www.youtube.com/watch?v=b1kbLwvqugk', ytId: 'b1kbLwvqugk', plays: '1.7M' },
  { rank: 5,  title: 'Hear Me Now',        artist: 'Alok',             url: 'https://www.youtube.com/watch?v=2MFGqoKd578', ytId: '2MFGqoKd578', plays: '1.5M' },
  { rank: 6,  title: 'Yellow',             artist: 'Coldplay',         url: 'https://www.youtube.com/watch?v=yKNxeF4KMsY', ytId: 'yKNxeF4KMsY', plays: '1.3M' },
  { rank: 7,  title: 'Perfect',            artist: 'Ed Sheeran',       url: 'https://www.youtube.com/watch?v=2Vv-BfVoq4g', ytId: '2Vv-BfVoq4g', plays: '1.2M' },
  { rank: 8,  title: 'Viva la Vida',       artist: 'Coldplay',         url: 'https://www.youtube.com/watch?v=dvgZkm1xWPE', ytId: 'dvgZkm1xWPE', plays: '1.1M' },
  { rank: 9,  title: 'Girl From Rio',      artist: 'Anitta',           url: 'https://www.youtube.com/watch?v=MlxpGEBqLCU', ytId: 'MlxpGEBqLCU', plays: '980K' },
  { rank: 10, title: 'Shake It Off',       artist: 'Taylor Swift',     url: 'https://www.youtube.com/watch?v=nfWlot6h_JM', ytId: 'nfWlot6h_JM', plays: '920K' },
];

/* ╔════════════════════════════════════════════════════════════╗
   ║  2. ESTADO GLOBAL DA APLICAÇÃO                            ║
   ╚════════════════════════════════════════════════════════════╝ */

const App = {
  currentUser:    null,   // Objeto do usuário logado
  currentPage:    'home', // Página ativa
  playlists:      [],     // Array de playlists do usuário
  processedItem:  null,   // Item atualmente processado no downloader
  addFormat:      null,   // Formato a ser adicionado à playlist ('mp3' | 'mp4')
  selectedTheme:  '🎵',  // Tema selecionado no modal de playlist
  editPlaylistId: null,   // ID da playlist sendo editada
  countdownRef:   null,   // Referência do setInterval do countdown
};

/* ╔════════════════════════════════════════════════════════════╗
   ║  3. INICIALIZAÇÃO                                         ║
   ╚════════════════════════════════════════════════════════════╝ */

document.addEventListener('DOMContentLoaded', () => {
  restoreSession();
  initHourlyRotation();
  updateGreeting();
});

/** Tenta restaurar sessão salva no LocalStorage */
function restoreSession() {
  try {
    const sessionData = localStorage.getItem(LS.SESSION);
    if (sessionData) {
      App.currentUser = JSON.parse(sessionData);
      bootApp();
    } else {
      // Mostra modal de autenticação
      showElement('authModal');
    }
  } catch (e) {
    showElement('authModal');
  }
}

/** Inicializa o app após login/sessão restaurada */
function bootApp() {
  App.playlists = loadPlaylists();
  updateSidebarUser();
  renderSidebarPlaylists();
  showElement('appShell');
  hideElement('authModal');
  navigateTo('home');
}

/* ╔════════════════════════════════════════════════════════════╗
   ║  4. AUTENTICAÇÃO                                          ║
   ║                                                           ║
   ║  TODO: Substituir por chamada de API (Fetch) ao migrar    ║
   ║        para o servidor da Base44.                         ║
   ║        Endpoint sugerido: POST /api/auth/login            ║
   ╚════════════════════════════════════════════════════════════╝ */

/**
 * Alterna entre aba de login e cadastro no modal de autenticação.
 * @param {'login'|'register'} tab
 */
function switchAuthTab(tab) {
  const isLogin = tab === 'login';
  el('tabLogin').classList.toggle('active', isLogin);
  el('tabRegister').classList.toggle('active', !isLogin);
  toggleVisibility('formLogin', isLogin);
  toggleVisibility('formRegister', !isLogin);
  el('loginError').textContent = '';
  el('registerError').textContent = '';
}

/**
 * TODO: Substituir por chamada de API (Fetch) ao migrar para o servidor da Base44.
 * Autentica o usuário usando dados do LocalStorage.
 * Na Base44: POST /api/auth/login { email, password } → { token, user }
 */
async function handleLogin() {
  const email = el('loginEmail').value.trim();
  const pass  = el('loginPass').value;
  el('loginError').textContent = '';

  if (!email || !pass) {
    el('loginError').textContent = 'Preencha e-mail e senha.';
    return;
  }

  // Simulação: busca usuário no LocalStorage
  const users = getUsers();
  const user = users.find(u => u.email === email && u.password === simpleHash(pass));

  if (!user) {
    el('loginError').textContent = 'E-mail ou senha incorretos.';
    return;
  }

  persistSession(user);
  showToast(`Bem-vindo(a) de volta, ${user.name}! 👋`, 'success');
  bootApp();
}

/**
 * TODO: Substituir por chamada de API (Fetch) ao migrar para o servidor da Base44.
 * Cria uma nova conta de usuário, persiste no LocalStorage.
 * Na Base44: POST /api/auth/register { name, email, password } → { token, user }
 */
async function handleRegister() {
  const name  = el('regName').value.trim();
  const email = el('regEmail').value.trim();
  const pass  = el('regPass').value;
  el('registerError').textContent = '';

  if (!name || !email || !pass) {
    el('registerError').textContent = 'Preencha todos os campos.';
    return;
  }
  if (!isValidEmail(email)) {
    el('registerError').textContent = 'E-mail inválido.';
    return;
  }
  if (pass.length < 6) {
    el('registerError').textContent = 'A senha precisa ter ao menos 6 caracteres.';
    return;
  }

  const users = getUsers();
  if (users.some(u => u.email === email)) {
    el('registerError').textContent = 'E-mail já cadastrado.';
    return;
  }

  const newUser = { id: uid(), name, email, password: simpleHash(pass), createdAt: Date.now() };
  users.push(newUser);
  localStorage.setItem(LS.USERS, JSON.stringify(users));

  persistSession(newUser);
  showToast(`Conta criada com sucesso! Bem-vindo(a), ${name}! 🎉`, 'success');
  bootApp();
}

/** Acesso como visitante (sem conta) */
function handleGuestAccess() {
  const guest = { id: 'guest', name: 'Visitante', email: 'guest@youfree.app', guest: true };
  App.currentUser = guest;
  showToast('Modo visitante. Dados não persistem.', 'info');
  bootApp();
}

/**
 * TODO: Substituir por chamada de API (Fetch) ao migrar para o servidor da Base44.
 * Encerra a sessão do usuário.
 * Na Base44: POST /api/auth/logout
 */
function handleLogout() {
  localStorage.removeItem(LS.SESSION);
  App.currentUser = null;
  App.playlists   = [];
  App.processedItem = null;
  hideElement('appShell');
  showElement('authModal');
  switchAuthTab('login');
  showToast('Sessão encerrada.', 'info');
}

/* ╔════════════════════════════════════════════════════════════╗
   ║  5. PROCESSAMENTO DE URL / DOWNLOADER                     ║
   ║                                                           ║
   ║  TODO: Substituir por chamada de API (Fetch) ao migrar    ║
   ║        para o servidor da Base44.                         ║
   ║        Endpoint sugerido: POST /api/media/info { url }    ║
   ╚════════════════════════════════════════════════════════════╝ */

/**
 * TODO: Substituir por chamada de API (Fetch) ao migrar para o servidor da Base44.
 * Processa um link do YouTube e exibe as informações de download.
 * Na Base44: POST /api/media/info { url } → { title, artist, duration, cover, formats }
 */
async function handleProcessUrl() {
  const urlInput = el('downloaderUrlInput');
  const rawUrl   = urlInput.value.trim();

  if (!rawUrl) {
    showToast('Cole um link do YouTube primeiro.', 'error');
    return;
  }
  if (!isYouTubeUrl(rawUrl)) {
    showToast('Por favor, informe um link válido do YouTube.', 'error');
    return;
  }

  // Exibe loader, esconde resultado anterior
  showElement('processingLoader');
  hideElement('resultCard');
  el('processBtn').disabled = true;
  hideElement('addToPlaylistMenu');

  // Simula latência de rede (1.8s – 2.8s)
  await sleep(randomInt(1800, 2800));

  // Simulação: seleciona um item aleatório do banco de vídeos
  const mockData = MOCK_VIDEOS[Math.floor(Math.random() * MOCK_VIDEOS.length)];

  App.processedItem = {
    id:       uid(),
    url:      rawUrl,
    title:    mockData.title,
    artist:   mockData.artist,
    duration: mockData.duration,
    cover:    mockData.cover,
  };

  // Preenche o card de resultado
  el('resultCover').src     = App.processedItem.cover;
  el('resultTitle').textContent    = App.processedItem.title;
  el('resultArtist').textContent   = App.processedItem.artist;
  el('resultDuration').textContent = `⏱ ${App.processedItem.duration}`;

  hideElement('processingLoader');
  showElement('resultCard');
  el('processBtn').disabled = false;

  // Garante aba MP3 ativa
  switchFormatTab('mp3');
  hideElement('addToPlaylistMenu');
}

/** Atalho para processar da barra de busca da home */
function processFromHome() {
  const url = el('homeUrlInput').value.trim();
  if (!url) { showToast('Cole um link do YouTube primeiro.', 'error'); return; }
  navigateTo('downloader');
  el('downloaderUrlInput').value = url;
  handleProcessUrl();
}

/**
 * TODO: Substituir por chamada de API (Fetch) ao migrar para o servidor da Base44.
 * Efetua o download do arquivo no formato e qualidade solicitados.
 * Na Base44:
 *   - MP3: POST /api/media/download { url, format:'mp3', quality:'320kbps' }
 *   - MP4: POST /api/media/download { url, format:'mp4', quality:'1080p' }
 *   A resposta deve ser um stream de arquivo ou uma URL de download assinada.
 *
 * @param {'mp3'|'mp4'} format
 * @param {string} quality  Ex: '320kbps', '360p', '720p', '1080p'
 */
async function handleDownload(format, quality) {
  if (!App.processedItem) { showToast('Nenhum item processado.', 'error'); return; }

  const item = App.processedItem;
  showToast(`⏬ Preparando download: ${item.title} (${format.toUpperCase()} ${quality})…`, 'info');

  // Simula delay de "geração do arquivo"
  await sleep(1200);

  /**
   * SIMULAÇÃO PARA GITHUB PAGES:
   * Como não há servidor, criamos um arquivo de texto simulando o download.
   * Na Base44, este bloco será substituído por um fetch que retorna um Blob.
   */
  const content = [
    `YouFree – Download Simulado`,
    `──────────────────────────────`,
    `Título:    ${item.title}`,
    `Artista:   ${item.artist}`,
    `Formato:   ${format.toUpperCase()}`,
    `Qualidade: ${quality}`,
    `URL:       ${item.url}`,
    `Data:      ${new Date().toLocaleString('pt-BR')}`,
    `──────────────────────────────`,
    `NOTA: Este é um download simulado (GitHub Pages).`,
    `Para downloads reais, acesse a versão hospedada na Base44.`,
  ].join('\n');

  const blob = new Blob([content], { type: 'text/plain' });
  const link = document.createElement('a');
  link.href  = URL.createObjectURL(blob);
  const safeName = item.title.replace(/[^a-z0-9]/gi, '_');
  link.download = `YouFree_${safeName}.${format === 'mp3' ? 'txt' : 'txt'}`;
  link.click();
  URL.revokeObjectURL(link.href);

  showToast(`✅ Download iniciado: ${item.title} (${format.toUpperCase()} ${quality})`, 'success');
}

/** Alterna entre as abas MP3 e MP4 no resultado */
function switchFormatTab(tab) {
  const isMp3 = tab === 'mp3';
  el('tabMp3').classList.toggle('active', isMp3);
  el('tabMp4').classList.toggle('active', !isMp3);
  toggleVisibility('panelMp3', isMp3);
  toggleVisibility('panelMp4', !isMp3);
  hideElement('addToPlaylistMenu');
}

/* ╔════════════════════════════════════════════════════════════╗
   ║  6. SISTEMA DE PLAYLISTS                                  ║
   ╚════════════════════════════════════════════════════════════╝ */

/** Carrega playlists do LocalStorage */
function loadPlaylists() {
  if (App.currentUser?.guest) return [];
  try {
    const key  = `${LS.PLAYLISTS}_${App.currentUser?.id}`;
    const data = localStorage.getItem(key);
    return data ? JSON.parse(data) : [];
  } catch { return []; }
}

/** Persiste playlists no LocalStorage */
function savePlaylists() {
  if (App.currentUser?.guest) return;
  const key = `${LS.PLAYLISTS}_${App.currentUser?.id}`;
  localStorage.setItem(key, JSON.stringify(App.playlists));
}

/** Abre o modal de nova playlist */
function openPlaylistModal(playlistId = null) {
  App.editPlaylistId = playlistId;
  const modal = el('playlistModal');

  if (playlistId) {
    const pl = App.playlists.find(p => p.id === playlistId);
    if (!pl) return;
    el('playlistModalTitle').textContent = 'Editar Playlist';
    el('playlistName').value = pl.name;
    App.selectedTheme = pl.theme;
  } else {
    el('playlistModalTitle').textContent = 'Nova Playlist';
    el('playlistName').value = '';
    App.selectedTheme = '🎵';
  }

  // Atualiza botões de tema
  document.querySelectorAll('.theme-btn').forEach(btn => {
    btn.classList.toggle('active', btn.dataset.theme === App.selectedTheme);
  });

  modal.classList.remove('hidden');
}

function closePlaylistModal() {
  el('playlistModal').classList.add('hidden');
  App.editPlaylistId = null;
}

function selectTheme(btn) {
  document.querySelectorAll('.theme-btn').forEach(b => b.classList.remove('active'));
  btn.classList.add('active');
  App.selectedTheme = btn.dataset.theme;
}

/** Salva (cria ou edita) uma playlist */
function savePlaylist() {
  const name = el('playlistName').value.trim();
  if (!name) { showToast('Digite um nome para a playlist.', 'error'); return; }

  if (App.editPlaylistId) {
    // Editar existente
    const idx = App.playlists.findIndex(p => p.id === App.editPlaylistId);
    if (idx >= 0) {
      App.playlists[idx].name  = name;
      App.playlists[idx].theme = App.selectedTheme;
    }
    showToast('Playlist atualizada! ✨', 'success');
  } else {
    // Criar nova
    App.playlists.push({ id: uid(), name, theme: App.selectedTheme, items: [], createdAt: Date.now() });
    showToast(`Playlist "${name}" criada! 🎵`, 'success');
  }

  savePlaylists();
  closePlaylistModal();
  renderSidebarPlaylists();
  if (App.currentPage === 'playlists') renderPlaylistsPage();
}

/** Remove uma playlist */
function deletePlaylist(id) {
  App.playlists = App.playlists.filter(p => p.id !== id);
  savePlaylists();
  renderSidebarPlaylists();
  renderPlaylistsPage();
  showToast('Playlist removida.', 'info');
}

/** Abre menu de escolha de playlist para adicionar item */
function openAddToPlaylistMenu(format) {
  if (!App.processedItem) return;
  App.addFormat = format;

  if (App.currentUser?.guest) {
    showToast('Faça login para salvar em playlists.', 'error');
    return;
  }

  const menu     = el('addToPlaylistMenu');
  const listEl   = el('addToPlaylistList');
  listEl.innerHTML = '';

  if (App.playlists.length === 0) {
    listEl.innerHTML = '<p style="color:var(--text-muted);font-size:0.85rem;padding:0.3rem 0.5rem;">Você ainda não tem playlists.</p>';
  } else {
    App.playlists.forEach(pl => {
      const btn = document.createElement('button');
      btn.className = 'pl-option-btn';
      btn.innerHTML = `<span>${pl.theme}</span><span>${pl.name}</span><span style="margin-left:auto;font-size:0.75rem;color:var(--text-muted)">${pl.items.length} itens</span>`;
      btn.onclick = () => addItemToPlaylist(pl.id);
      listEl.appendChild(btn);
    });
  }

  menu.classList.toggle('hidden');
}

/** Adiciona o item processado a uma playlist */
function addItemToPlaylist(playlistId) {
  const pl = App.playlists.find(p => p.id === playlistId);
  if (!pl || !App.processedItem) return;

  const already = pl.items.some(i => i.url === App.processedItem.url && i.format === App.addFormat);
  if (already) {
    showToast('Este item já está nessa playlist.', 'info');
    return;
  }

  pl.items.push({ ...App.processedItem, format: App.addFormat, addedAt: Date.now() });
  savePlaylists();
  renderSidebarPlaylists();
  hideElement('addToPlaylistMenu');
  showToast(`Adicionado à "${pl.name}"! 🎶`, 'success');
}

/** Remove item de uma playlist */
function removeItemFromPlaylist(playlistId, itemId) {
  const pl = App.playlists.find(p => p.id === playlistId);
  if (!pl) return;
  pl.items = pl.items.filter(i => i.id !== itemId);
  savePlaylists();
  renderPlaylistDetail(playlistId);
  showToast('Item removido da playlist.', 'info');
}

/* ╔════════════════════════════════════════════════════════════╗
   ║  7. NAVEGAÇÃO E ROTEAMENTO                                ║
   ╚════════════════════════════════════════════════════════════╝ */

/**
 * Navega para uma página da SPA.
 * @param {'home'|'downloader'|'playlists'|'explore'} page
 */
function navigateTo(page) {
  App.currentPage = page;

  // Esconde todas as páginas
  document.querySelectorAll('.page').forEach(p => {
    p.classList.add('hidden');
    p.classList.remove('active');
  });

  // Exibe página alvo
  const target = el(`page-${page}`);
  if (target) { target.classList.remove('hidden'); target.classList.add('active'); }

  // Atualiza itens de navegação ativos (sidebar + bottom)
  document.querySelectorAll('[data-page]').forEach(btn => {
    btn.classList.toggle('active', btn.dataset.page === page);
  });

  // Lógica específica de cada página
  switch (page) {
    case 'home':       renderHomePage();      break;
    case 'playlists':  renderPlaylistsPage(); break;
    case 'explore':    renderExplorePage();   break;
  }

  // Scroll ao topo
  el('mainContent')?.scrollTo({ top: 0, behavior: 'smooth' });
}

/* ╔════════════════════════════════════════════════════════════╗
   ║  8. RENDERIZAÇÃO – HOME                                   ║
   ╚════════════════════════════════════════════════════════════╝ */

function renderHomePage() {
  updateGreeting();
  renderAlbums('albumGrid', getHourlyGroup(ALBUM_BANK));
  renderArtists('artistsGrid', getHourlyGroup(ARTIST_BANK));
  renderTop10(); // 🔥 Top 10 Bombando no YouFree
}

/** Renderiza grid horizontal de álbuns */
function renderAlbums(containerId, albums) {
  const container = el(containerId);
  if (!container) return;
  container.innerHTML = '';
  albums.forEach(album => {
    const card = document.createElement('div');
    card.className = 'album-card';
    card.innerHTML = `
      <div class="album-cover-wrap">
        <img src="${album.cover}" alt="${escHtml(album.title)}" loading="lazy" />
        <button class="album-play-btn" aria-label="Reproduzir ${escHtml(album.title)}"
          onclick="quickDownloadAlbum(event, '${escHtml(album.url)}')">
          <svg viewBox="0 0 24 24" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"/></svg>
        </button>
      </div>
      <div class="album-title">${escHtml(album.title)}</div>
      <div class="album-artist">${escHtml(album.artist)}</div>
    `;
    container.appendChild(card);
  });
}

/** Renderiza grid de artistas */
function renderArtists(containerId, artists) {
  const container = el(containerId);
  if (!container) return;
  container.innerHTML = '';
  artists.forEach(artist => {
    const card = document.createElement('div');
    card.className = 'artist-card';
    card.innerHTML = `
      <div class="artist-avatar">
        <img src="${artist.avatar}" alt="${escHtml(artist.name)}" loading="lazy" />
      </div>
      <div class="artist-name">${escHtml(artist.name)}</div>
      <div class="artist-genre">${escHtml(artist.genre)}</div>
    `;
    container.appendChild(card);
  });
}

/** Atalho: clica em um álbum da home → abre downloader com URL */
function quickDownloadAlbum(e, url) {
  e.stopPropagation();
  navigateTo('downloader');
  el('downloaderUrlInput').value = url;
  handleProcessUrl();
}

/* ╔════════════════════════════════════════════════════════════╗
   ║  9. RENDERIZAÇÃO – PLAYLISTS                              ║
   ╚════════════════════════════════════════════════════════════╝ */

function renderPlaylistsPage() {
  const grid   = el('playlistsGrid');
  const detail = el('playlistDetail');

  showElement('playlistsGrid');
  detail.classList.add('hidden');
  detail.innerHTML = '';

  grid.innerHTML = '';

  if (App.currentUser?.guest) {
    grid.innerHTML = `
      <div class="empty-state" style="grid-column:1/-1">
        <div class="empty-state-icon">🔒</div>
        <h3>Faça login para usar playlists</h3>
        <p>Crie uma conta gratuita e organize sua música favorita.</p>
        <button class="btn-primary" onclick="handleLogout()" style="margin-top:0.75rem">Entrar / Cadastrar</button>
      </div>`;
    return;
  }

  if (App.playlists.length === 0) {
    grid.innerHTML = `
      <div class="empty-state" style="grid-column:1/-1">
        <div class="empty-state-icon">🎵</div>
        <h3>Nenhuma playlist ainda</h3>
        <p>Crie sua primeira playlist e comece a organizar sua música.</p>
        <button class="btn-primary" onclick="openPlaylistModal()" style="margin-top:0.75rem">
          + Criar Playlist
        </button>
      </div>`;
    return;
  }

  App.playlists.forEach(pl => {
    const card = document.createElement('div');
    card.className = 'playlist-card';
    card.onclick = () => renderPlaylistDetail(pl.id);
    card.innerHTML = `
      <div class="playlist-card-actions">
        <button class="playlist-action-btn" title="Editar" onclick="event.stopPropagation(); openPlaylistModal('${pl.id}')">✏️</button>
        <button class="playlist-action-btn" title="Excluir" onclick="event.stopPropagation(); confirmDeletePlaylist('${pl.id}')">🗑</button>
      </div>
      <span class="playlist-emoji">${pl.theme}</span>
      <div class="playlist-card-name">${escHtml(pl.name)}</div>
      <div class="playlist-card-count">${pl.items.length} ${pl.items.length === 1 ? 'item' : 'itens'}</div>
    `;
    grid.appendChild(card);
  });
}

/** Renderiza o detalhe de uma playlist */
function renderPlaylistDetail(playlistId) {
  const pl     = App.playlists.find(p => p.id === playlistId);
  const grid   = el('playlistsGrid');
  const detail = el('playlistDetail');

  if (!pl) return;

  hideElement('playlistsGrid');
  detail.classList.remove('hidden');

  const itemsHtml = pl.items.length > 0
    ? `<div class="playlist-items-list">
        ${pl.items.map((item, idx) => `
          <div class="playlist-item">
            <span class="playlist-item-num">${idx + 1}</span>
            <img class="playlist-item-cover" src="${escHtml(item.cover)}" alt="${escHtml(item.title)}" loading="lazy" />
            <div class="playlist-item-info">
              <div class="playlist-item-title">${escHtml(item.title)}</div>
              <div class="playlist-item-meta">
                ${escHtml(item.artist)} · ${escHtml(item.duration)}
                <span class="playlist-item-format item-fmt-${item.format}">${item.format.toUpperCase()}</span>
              </div>
            </div>
            <button class="playlist-item-remove" title="Remover"
              onclick="removeItemFromPlaylist('${pl.id}', '${item.id}')">✕</button>
          </div>
        `).join('')}
       </div>`
    : `<div class="empty-state">
         <div class="empty-state-icon">🎧</div>
         <h3>Playlist vazia</h3>
         <p>Processe um link no downloader e adicione aqui.</p>
         <button class="btn-primary" onclick="navigateTo('downloader')" style="margin-top:0.75rem">Ir para o Downloader</button>
       </div>`;

  detail.innerHTML = `
    <button class="playlist-back-btn" onclick="renderPlaylistsPage()">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <polyline points="15 18 9 12 15 6"/>
      </svg>
      Voltar
    </button>
    <div class="playlist-detail-header">
      <div class="playlist-detail-emoji">${pl.theme}</div>
      <div class="playlist-detail-info">
        <h2>${escHtml(pl.name)}</h2>
        <p>${pl.items.length} ${pl.items.length === 1 ? 'item' : 'itens'} · Criada em ${new Date(pl.createdAt).toLocaleDateString('pt-BR')}</p>
      </div>
    </div>
    ${pl.items.length > 0 ? `
      <button class="playlist-dl-all" onclick="downloadAllFromPlaylist('${pl.id}')">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="width:16px;height:16px">
          <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/>
          <polyline points="7 10 12 15 17 10"/>
          <line x1="12" y1="15" x2="12" y2="3"/>
        </svg>
        Baixar Tudo
      </button>
    ` : ''}
    ${itemsHtml}
  `;
}

/** Confirmação simples antes de deletar playlist */
function confirmDeletePlaylist(id) {
  const pl = App.playlists.find(p => p.id === id);
  if (!pl) return;
  if (window.confirm(`Excluir a playlist "${pl.name}"? Esta ação não pode ser desfeita.`)) {
    deletePlaylist(id);
  }
}

/**
 * TODO: Substituir por chamada de API (Fetch) ao migrar para o servidor da Base44.
 * Simula o download de todos os itens de uma playlist.
 * Na Base44: POST /api/media/batch-download { items: [...] }
 */
async function downloadAllFromPlaylist(playlistId) {
  const pl = App.playlists.find(p => p.id === playlistId);
  if (!pl || pl.items.length === 0) return;
  showToast(`⬇️ Iniciando download de ${pl.items.length} itens de "${pl.name}"…`, 'info');
  await sleep(600);
  showToast(`✅ ${pl.items.length} downloads iniciados! (Simulado)`, 'success');
}

/* ╔════════════════════════════════════════════════════════════╗
   ║  10. RENDERIZAÇÃO – EXPLORAR                              ║
   ╚════════════════════════════════════════════════════════════╝ */

function renderExplorePage() {
  // Exibe todos os grupos de álbuns combinados
  const allAlbums   = ALBUM_BANK.flat();
  const allArtists  = ARTIST_BANK.flat();
  renderAlbums('exploreAlbumGrid', allAlbums);
  renderArtists('exploreArtistsGrid', allArtists);
}

/* ╔════════════════════════════════════════════════════════════╗
   ║  11. ROTAÇÃO HORÁRIA DE CONTEÚDO                          ║
   ╚════════════════════════════════════════════════════════════╝ */

/**
 * Retorna o grupo de conteúdo correspondente à hora atual.
 * Muda a cada hora cheia, simulando atualização de API em tempo real.
 * @param {Array[]} bank - Array de grupos
 * @returns {Array} Grupo atual
 */
function getHourlyGroup(bank) {
  const hour = new Date().getHours();
  return bank[hour % bank.length];
}

/**
 * Inicializa o sistema de rotação horária.
 * Verifica a cada minuto se a hora mudou; se sim, atualiza o conteúdo.
 */
function initHourlyRotation() {
  let lastHour = new Date().getHours();

  // Atualiza countdown a cada segundo
  App.countdownRef = setInterval(() => {
    updateCountdown();
    const currentHour = new Date().getHours();
    if (currentHour !== lastHour) {
      lastHour = currentHour;
      // Hora mudou: re-renderiza conteúdo da home e explorar
      if (App.currentPage === 'home')    renderHomePage();
      if (App.currentPage === 'explore') renderExplorePage();
      showToast('🔄 Conteúdo atualizado!', 'info');
    }
  }, 1000);
}

/** Atualiza o countdown até a próxima hora cheia */
function updateCountdown() {
  const badge = el('countdownTimer');
  if (!badge) return;
  const now  = new Date();
  const next = new Date(now);
  next.setHours(now.getHours() + 1, 0, 0, 0);
  const diff = Math.max(0, next - now);
  const mm   = String(Math.floor(diff / 60000)).padStart(2, '0');
  const ss   = String(Math.floor((diff % 60000) / 1000)).padStart(2, '0');
  badge.textContent = `${mm}:${ss}`;
}

/* ╔════════════════════════════════════════════════════════════╗
   ║  12. SIDEBAR – RENDERIZAÇÃO DINÂMICA                      ║
   ╚════════════════════════════════════════════════════════════╝ */

function renderSidebarPlaylists() {
  const list = el('sidebarPlaylistList');
  if (!list) return;
  list.innerHTML = '';

  if (!App.currentUser || App.currentUser.guest || App.playlists.length === 0) {
    list.innerHTML = `<p style="font-size:0.78rem;color:var(--text-muted);padding:0.3rem 0.5rem;">Nenhuma playlist</p>`;
    return;
  }

  App.playlists.forEach(pl => {
    const item = document.createElement('div');
    item.className = 'sidebar-pl-item';
    item.innerHTML = `<span class="sidebar-pl-emoji">${pl.theme}</span><span>${escHtml(pl.name)}</span>`;
    item.onclick = () => { navigateTo('playlists'); renderPlaylistDetail(pl.id); };
    list.appendChild(item);
  });
}

function updateSidebarUser() {
  const user = App.currentUser;
  if (!user) return;
  const initial = user.name ? user.name[0].toUpperCase() : 'Y';
  el('sidebarAvatar').textContent    = initial;
  el('sidebarUserName').textContent  = user.name;
  el('sidebarUserEmail').textContent = user.email;
}

/* ╔════════════════════════════════════════════════════════════╗
   ║  13. SAUDAÇÃO DINÂMICA                                    ║
   ╚════════════════════════════════════════════════════════════╝ */

function updateGreeting() {
  const hour = new Date().getHours();
  let greeting;
  if (hour >= 5  && hour < 12) greeting = 'Bom dia! ☀️';
  else if (hour >= 12 && hour < 18) greeting = 'Boa tarde! 🌤️';
  else if (hour >= 18 && hour < 22) greeting = 'Boa noite! 🌆';
  else greeting = 'Olá! 🌙';

  const titleEl = el('greetingTitle');
  if (titleEl) titleEl.textContent = greeting;
}

/* ╔════════════════════════════════════════════════════════════╗
   ║  14. UTILITÁRIOS DE UI                                    ║
   ╚════════════════════════════════════════════════════════════╝ */

/** Mostra um toast de notificação */
function showToast(msg, type = '') {
  const toast = el('toast');
  toast.textContent = msg;
  toast.className   = `toast${type ? ' ' + type : ''}`;
  toast.classList.add('show');
  setTimeout(() => toast.classList.remove('show'), 3400);
}

function showElement(id) { el(id)?.classList.remove('hidden'); }
function hideElement(id) { el(id)?.classList.add('hidden'); }
function toggleVisibility(id, show) { show ? showElement(id) : hideElement(id); }
function el(id) { return document.getElementById(id); }

/* ╔════════════════════════════════════════════════════════════╗
   ║  15. UTILITÁRIOS GERAIS                                   ║
   ╚════════════════════════════════════════════════════════════╝ */

/** Gera ID único simples */
function uid() {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
}

/** Sleep assíncrono */
function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

/** Número aleatório inteiro em [min, max] */
function randomInt(min, max) { return Math.floor(Math.random() * (max - min + 1)) + min; }

/**
 * Hash simples para senhas (APENAS para simulação no LocalStorage).
 * Na Base44, usar bcrypt ou similar no servidor.
 */
function simpleHash(str) {
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    const c = str.charCodeAt(i);
    hash = ((hash << 5) - hash) + c;
    hash |= 0;
  }
  return hash.toString(16);
}

/** Valida formato de e-mail */
function isValidEmail(email) {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email);
}

/** Valida se é URL do YouTube */
function isYouTubeUrl(url) {
  try {
    const u = new URL(url);
    return ['youtube.com', 'www.youtube.com', 'youtu.be', 'm.youtube.com'].includes(u.hostname);
  } catch {
    // Se não for URL válida, permite processar mesmo assim (demo)
    return true;
  }
}

/** Escapa HTML para prevenir XSS */
function escHtml(str) {
  if (!str) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/** Carrega lista de usuários do LocalStorage */
function getUsers() {
  try {
    const data = localStorage.getItem(LS.USERS);
    return data ? JSON.parse(data) : [];
  } catch { return []; }
}

/** Persiste a sessão do usuário */
function persistSession(user) {
  App.currentUser = user;
  if (!user.guest) {
    // Não persistir senha na sessão
    const sessionUser = { ...user };
    delete sessionUser.password;
    localStorage.setItem(LS.SESSION, JSON.stringify(sessionUser));
  }
}

/* ╔════════════════════════════════════════════════════════════╗
   ║  16. FECHAMENTO DE MODAIS AO CLICAR FORA                  ║
   ╚════════════════════════════════════════════════════════════╝ */

document.addEventListener('click', (e) => {
  // Fecha menu de "adicionar à playlist" ao clicar fora
  const menu = el('addToPlaylistMenu');
  if (menu && !menu.classList.contains('hidden')) {
    if (!menu.contains(e.target) &&
        !e.target.closest('[onclick*="openAddToPlaylistMenu"]')) {
      hideElement('addToPlaylistMenu');
    }
  }

  // Fecha modal de playlist ao clicar no overlay
  const plModal = el('playlistModal');
  if (plModal && !plModal.classList.contains('hidden')) {
    if (e.target === plModal) closePlaylistModal();
  }
});

/* ╔════════════════════════════════════════════════════════════╗
   ║  17. TECLA ESC FECHA MODAIS                               ║
   ╚════════════════════════════════════════════════════════════╝ */

document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') {
    closePlaylistModal();
    hideElement('addToPlaylistMenu');
  }
});

/* ╔════════════════════════════════════════════════════════════╗
   ║  18. ENTER NOS CAMPOS DE URL                              ║
   ╚════════════════════════════════════════════════════════════╝ */

document.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') {
    if (document.activeElement?.id === 'downloaderUrlInput') handleProcessUrl();
    if (document.activeElement?.id === 'homeUrlInput')       processFromHome();
    if (document.activeElement?.id === 'loginPass')          handleLogin();
    if (document.activeElement?.id === 'regPass')            handleRegister();
  }
});


/* ╔════════════════════════════════════════════════════════════╗
   ║  BUSCA POR ARTISTA – Catálogo e Integração Downloader     ║
   ╚════════════════════════════════════════════════════════════╝ */

/**
 * Busca um artista no ARTIST_CATALOG pelo nome (case-insensitive, parcial).
 * Exibe os resultados na seção #artistCatalogSection.
 */
function searchArtist() {
  const query = (document.getElementById('artistSearchInput') || {}).value || '';
  const term = query.trim().toLowerCase();
  const section = document.getElementById('artistCatalogSection');
  if (!section) return;

  if (!term) {
    section.innerHTML = '';
    section.classList.add('hidden');
    return;
  }

  const found = ARTIST_CATALOG.filter(a => a.name.toLowerCase().includes(term));

  if (found.length === 0) {
    section.innerHTML = `
      <div class="artist-not-found">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" width="48" height="48"><circle cx="11" cy="11" r="8"/><path d="M21 21l-4.35-4.35"/></svg>
        <p>Nenhum artista encontrado para "<strong>${query}</strong>".</p>
        <span>Tente: Coldplay, Ed Sheeran, Taylor Swift, Anitta, Alok</span>
      </div>`;
    section.classList.remove('hidden');
    return;
  }

  section.innerHTML = found.map(artist => renderArtistCard(artist)).join('');
  section.classList.remove('hidden');
  section.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

/**
 * Gera o HTML do card de um artista com sua lista de músicas.
 */

function handleSongClick(el) {
  const url = decodeURIComponent(el.dataset.url || '');
  const title = decodeURIComponent(el.dataset.title || '');
  if (url) copyAndFillLink(url, title, el);
}


function copyAndFillLink(url, title, el) {
  // Preenche o campo de download principal na home
  const urlInput = document.getElementById('homeUrlInput');
  if (urlInput) {
    urlInput.value = url;
    urlInput.focus();
  }

  // Copia para clipboard
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(url)
      .then(() => showCopyToast(title, el))
      .catch(() => fallbackCopy(url, title, el));
  } else {
    fallbackCopy(url, title, el);
  }
}
function fallbackCopy(url, title, el) {
  const ta = document.createElement('textarea');
  ta.value = url;
  ta.style.position = 'fixed';
  ta.style.opacity = '0';
  document.body.appendChild(ta);
  ta.focus();
  ta.select();
  try { document.execCommand('copy'); } catch(e) {}
  document.body.removeChild(ta);
  showCopyToast(title, el);
}

function showCopyToast(title, el) {
  // Remove existing toasts
  document.querySelectorAll('.copy-toast').forEach(t => t.remove());

  const toast = document.createElement('div');
  toast.className = 'copy-toast';
  toast.innerHTML = `
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" width="18" height="18">
      <polyline points="20 6 9 17 4 12"/>
    </svg>
    <span>Link copiado! Cole acima para baixar.</span>
  `;
  document.body.appendChild(toast);

  // Highlight the clicked row
  if (el) {
    const row = el.closest ? el.closest('.artist-song-item') : el;
    if (row) {
      row.classList.add('song-copied');
      setTimeout(() => row.classList.remove('song-copied'), 2000);
    }
  }

  requestAnimationFrame(() => toast.classList.add('copy-toast--visible'));
  setTimeout(() => {
    toast.classList.remove('copy-toast--visible');
    setTimeout(() => toast.remove(), 400);
  }, 3000);
}

// Allow pressing Enter in the artist search field
document.addEventListener('DOMContentLoaded', () => {
  const input = document.getElementById('artistSearchInput');
  if (input) {
    input.addEventListener('keydown', e => {
      if (e.key === 'Enter') searchArtist();
    });
  }
});


/* ══════════════════════════════════════════════════════════════
   🔥 TOP 10 – RENDERIZAÇÃO
   ══════════════════════════════════════════════════════════════ */

/**
 * Renderiza a lista Top 10 no elemento #top10List.
 * Chamado no boot do app (renderHomePage).
 */
function renderTop10() {
  const container = document.getElementById('top10List');
  if (!container) return;

  container.innerHTML = TOP10_TRACKS.map(track => {
    const rankClass = track.rank <= 3 ? 'top3' : '';
    return `
      <li class="top10-item ${rankClass}" data-url="${encodeURIComponent(track.url)}" data-ytid="${track.ytId}" data-title="${encodeURIComponent(track.title)}" data-artist="${encodeURIComponent(track.artist)}">
        <span class="top10-rank">${track.rank <= 3 ? ['🥇','🥈','🥉'][track.rank-1] : track.rank}</span>
        <div class="top10-info">
          <span class="top10-title">${track.title}</span>
          <span class="top10-artist">${track.artist}</span>
        </div>
        <span class="top10-plays">${track.plays}</span>
        <div class="top10-actions">
          <button class="top10-btn-play" onclick="event.stopPropagation();miniPlayerOpen(decodeURIComponent(this.closest('[data-ytid]').dataset.ytid), decodeURIComponent(this.closest('[data-title]').dataset.title), decodeURIComponent(this.closest('[data-artist]').dataset.artist), decodeURIComponent(this.closest('[data-url]').dataset.url))" aria-label="Preview de ${track.title}">
            <svg viewBox="0 0 24 24" fill="currentColor" width="16" height="16"><path d="M8 5v14l11-7z"/></svg>
          </button>
          <button class="top10-btn-copy" onclick="event.stopPropagation();handleSongClick(this.closest('.top10-item'))" aria-label="Copiar link de ${track.title}">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="15" height="15"><path d="M10 13a5 5 0 007.54.54l3-3a5 5 0 00-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 00-7.54-.54l-3 3a5 5 0 007.07 7.07l1.71-1.7"/></svg>
            Copiar
          </button>
        </div>
      </li>
    `;
  }).join('');

  // Add click handler for the whole row
  container.querySelectorAll('.top10-item').forEach(item => {
    item.addEventListener('click', () => {
      const url = decodeURIComponent(item.dataset.url);
      const title = decodeURIComponent(item.dataset.title);
      const artist = decodeURIComponent(item.dataset.artist);
      const ytId = item.dataset.ytid;
      copyAndFillLink(url, title, item);
      miniPlayerOpen(ytId, title, artist, url);
    });
  });
}

/* ══════════════════════════════════════════════════════════════
   🎵 MINI PLAYER – LÓGICA COMPLETA
   ══════════════════════════════════════════════════════════════ */

/** Estado interno do Mini Player */
const MiniPlayer = {
  queue: [],        // Array de {ytId, title, artist, url}
  currentIdx: 0,
  isPlaying: false,
  progressTimer: null,
  elapsed: 0,
  duration: 210,    // 3:30 default (preview simulado)
};

/**
 * Abre o mini-player com a faixa especificada.
 * @param {string} ytId   - ID do vídeo YouTube
 * @param {string} title  - Título da música
 * @param {string} artist - Nome do artista
 * @param {string} url    - URL completa do YouTube
 */
function miniPlayerOpen(ytId, title, artist, url) {
  // Adiciona à fila se não estiver
  const existingIdx = MiniPlayer.queue.findIndex(t => t.ytId === ytId);
  if (existingIdx >= 0) {
    MiniPlayer.currentIdx = existingIdx;
  } else {
    MiniPlayer.queue.push({ ytId, title, artist, url });
    MiniPlayer.currentIdx = MiniPlayer.queue.length - 1;
  }

  const player = document.getElementById('miniPlayer');
  if (player) {
    player.classList.remove('hidden');
    player.classList.add('mini-player--visible');
  }

  _miniPlayerLoadTrack(MiniPlayer.currentIdx);
}

/** Carrega a faixa no índice especificado */
function _miniPlayerLoadTrack(idx) {
  if (idx < 0 || idx >= MiniPlayer.queue.length) return;
  MiniPlayer.currentIdx = idx;

  const track = MiniPlayer.queue[idx];

  // Atualiza UI
  const titleEl = document.getElementById('miniPlayerTitle');
  const artistEl = document.getElementById('miniPlayerArtist');
  if (titleEl) titleEl.textContent = track.title;
  if (artistEl) artistEl.textContent = track.artist;

  // Reset progresso
  MiniPlayer.elapsed = 0;
  _miniPlayerUpdateProgress();

  // Carrega YouTube iframe (embed com autoplay, muted=0, start=0)
  const iframe = document.getElementById('ytPreviewFrame');
  if (iframe) {
    // Usa embed com autoplay e sem controles visíveis
    iframe.src = `https://www.youtube-nocookie.com/embed/${track.ytId}?autoplay=1&controls=0&modestbranding=1&rel=0&iv_load_policy=3&enablejsapi=0`;
  }

  MiniPlayer.isPlaying = true;
  _miniPlayerSetPlayState(true);
  _miniPlayerStartTimer();
}

/** Inicia o timer de progresso simulado */
function _miniPlayerStartTimer() {
  if (MiniPlayer.progressTimer) clearInterval(MiniPlayer.progressTimer);
  MiniPlayer.progressTimer = setInterval(() => {
    if (!MiniPlayer.isPlaying) return;
    MiniPlayer.elapsed++;
    if (MiniPlayer.elapsed >= MiniPlayer.duration) {
      MiniPlayer.elapsed = 0;
      miniPlayerNext();
      return;
    }
    _miniPlayerUpdateProgress();
  }, 1000);
}

/** Atualiza a barra de progresso e o tempo */
function _miniPlayerUpdateProgress() {
  const fill = document.getElementById('mpProgressFill');
  const currentTimeEl = document.getElementById('mpCurrentTime');
  const durationEl = document.getElementById('mpDuration');

  const pct = Math.min((MiniPlayer.elapsed / MiniPlayer.duration) * 100, 100);
  if (fill) fill.style.width = pct + '%';

  const fmt = s => `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
  if (currentTimeEl) currentTimeEl.textContent = fmt(MiniPlayer.elapsed);
  if (durationEl) durationEl.textContent = fmt(MiniPlayer.duration);
}

/** Alterna play/pause */
function miniPlayerToggle() {
  MiniPlayer.isPlaying = !MiniPlayer.isPlaying;
  _miniPlayerSetPlayState(MiniPlayer.isPlaying);

  const iframe = document.getElementById('ytPreviewFrame');
  if (iframe) {
    if (!MiniPlayer.isPlaying) {
      // Pausa: remove src temporariamente
      iframe.dataset.lastSrc = iframe.src;
      iframe.src = '';
    } else {
      // Retoma
      iframe.src = iframe.dataset.lastSrc || iframe.src;
    }
  }
}

/** Atualiza ícones play/pause */
function _miniPlayerSetPlayState(playing) {
  const playIcon = document.getElementById('mpPlayIcon');
  const pauseIcon = document.getElementById('mpPauseIcon');
  if (playIcon) playIcon.style.display = playing ? 'none' : 'block';
  if (pauseIcon) pauseIcon.style.display = playing ? 'block' : 'none';
}

/** Vai para a próxima faixa */
function miniPlayerNext() {
  if (MiniPlayer.queue.length === 0) return;
  const nextIdx = (MiniPlayer.currentIdx + 1) % MiniPlayer.queue.length;
  _miniPlayerLoadTrack(nextIdx);
}

/** Vai para a faixa anterior */
function miniPlayerPrev() {
  if (MiniPlayer.queue.length === 0) return;
  const prevIdx = (MiniPlayer.currentIdx - 1 + MiniPlayer.queue.length) % MiniPlayer.queue.length;
  _miniPlayerLoadTrack(prevIdx);
}

/** Copia o link da faixa atual para o clipboard e preenche o campo */
function miniPlayerCopyLink() {
  if (MiniPlayer.queue.length === 0) return;
  const track = MiniPlayer.queue[MiniPlayer.currentIdx];
  copyAndFillLink(track.url, track.title, null);
}

/** Fecha o mini player */
function miniPlayerClose() {
  if (MiniPlayer.progressTimer) clearInterval(MiniPlayer.progressTimer);
  MiniPlayer.isPlaying = false;
  MiniPlayer.queue = [];
  MiniPlayer.currentIdx = 0;

  const iframe = document.getElementById('ytPreviewFrame');
  if (iframe) iframe.src = '';

  const player = document.getElementById('miniPlayer');
  if (player) {
    player.classList.remove('mini-player--visible');
    setTimeout(() => player.classList.add('hidden'), 400);
  }
}

/* ══════════════════════════════════════════════════════════════
   🎵 BOTÃO PLAY NAS LISTAS DE MÚSICAS (artistas + top10)
   ══════════════════════════════════════════════════════════════ */

/**
 * Override de renderArtistCard para incluir botão play em cada música.
 * Substitui a função renderArtistCard anterior com botão de play.
 */
function renderArtistCard(artist) {
  const songs = artist.songs.map((song, i) => {
    const safeUrl = encodeURIComponent(song.url);
    const safeTitle = encodeURIComponent(song.title);
    const safeArtist = encodeURIComponent(artist.name);
    // Extrai o ytId da URL do YouTube
    const ytId = song.url.split('v=')[1] || song.url.split('/').pop() || '';
    return `
      <li class="artist-song-item" data-url="${safeUrl}" data-title="${safeTitle}" data-artist="${safeArtist}" data-ytid="${ytId}" onclick="handleSongClick(this)">
        <span class="song-index">${String(i + 1).padStart(2, '0')}</span>
        <button class="song-play-btn" onclick="event.stopPropagation();miniPlayerOpen('${ytId}', decodeURIComponent('${safeTitle}'), decodeURIComponent('${safeArtist}'), decodeURIComponent('${safeUrl}'))" aria-label="Preview de ${song.title}">
          <svg viewBox="0 0 24 24" fill="currentColor" width="14" height="14"><path d="M8 5v14l11-7z"/></svg>
        </button>
        <span class="song-title">${song.title}</span>
        <button class="btn-copy-link" onclick="event.stopPropagation();handleSongClick(this.parentElement)" aria-label="Copiar link">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><path d="M10 13a5 5 0 007.54.54l3-3a5 5 0 00-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 00-7.54-.54l-3 3a5 5 0 007.07 7.07l1.71-1.7"/></svg>
          Copiar Link
        </button>
      </li>
    `;
  }).join('');

  return `
    <div class="artist-catalog-card">
      <div class="artist-catalog-header">
        <div class="artist-catalog-photo-wrap">
          <img src="${artist.photo}" alt="${artist.name}" class="artist-catalog-photo"
            onerror="this.src='https://images.unsplash.com/photo-1493225457124-a3eb161ffa5f?w=400&h=400&fit=crop'" />
          <div class="artist-catalog-overlay">
            <svg viewBox="0 0 24 24" fill="currentColor" width="32" height="32"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 14.5v-9l6 4.5-6 4.5z"/></svg>
          </div>
        </div>
        <div class="artist-catalog-info">
          <h2 class="artist-catalog-name">${artist.name}</h2>
          <span class="artist-catalog-genre">${artist.genre}</span>
          <p class="artist-catalog-hint">▶ Play para prévia · 🔗 Copiar para baixar</p>
        </div>
      </div>
      <ol class="artist-song-list">${songs}</ol>
    </div>
  `;
}

/* ══════════════════════════════════════════════════════════════
   📲 PWA – INSTALAÇÃO E SERVICE WORKER
   ══════════════════════════════════════════════════════════════ */

let _pwaInstallPrompt = null;

/** Registra o Service Worker */
function registerServiceWorker() {
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('/YOUFREE/sw.js', { scope: '/YOUFREE/' })
      .then(reg => {
        console.log('[YouFree] Service Worker registrado:', reg.scope);
      })
      .catch(err => console.warn('[YouFree] SW falhou:', err));
  }
}

/** Captura o evento beforeinstallprompt para mostrar o banner customizado */
function initPWAInstall() {
  window.addEventListener('beforeinstallprompt', e => {
    e.preventDefault();
    _pwaInstallPrompt = e;

    // Mostra o banner de instalação após 3 segundos
    const dismissed = sessionStorage.getItem('yf_pwa_dismissed');
    if (!dismissed) {
      setTimeout(() => {
        const banner = document.getElementById('pwaInstallBanner');
        if (banner) banner.classList.remove('hidden');
      }, 3000);
    }
  });

  // Quando instalado com sucesso
  window.addEventListener('appinstalled', () => {
    console.log('[YouFree] App instalado com sucesso!');
    pwaDismiss();
    showToast('YouFree instalado! Acesse pela tela inicial. 🎵');
  });
}

/** Dispara o prompt de instalação nativo */
function pwaInstall() {
  if (_pwaInstallPrompt) {
    _pwaInstallPrompt.prompt();
    _pwaInstallPrompt.userChoice.then(choice => {
      if (choice.outcome === 'accepted') {
        console.log('[YouFree] Usuário aceitou a instalação');
      }
      _pwaInstallPrompt = null;
      pwaDismiss();
    });
  }
}

/** Fecha o banner de instalação */
function pwaDismiss() {
  const banner = document.getElementById('pwaInstallBanner');
  if (banner) {
    banner.classList.add('pwa-banner--hiding');
    setTimeout(() => banner.classList.add('hidden'), 400);
  }
  sessionStorage.setItem('yf_pwa_dismissed', '1');
}

/* ══════════════════════════════════════════════════════════════
   INTEGRAÇÃO: INIT EXTENSIONS (chamado no bootApp)
   ══════════════════════════════════════════════════════════════ */

/** Patch no bootApp para inicializar os novos módulos */
const _originalBootApp = window.bootApp;

document.addEventListener('DOMContentLoaded', () => {
  // Registra SW
  registerServiceWorker();
  // Inicia listener PWA
  initPWAInstall();
});

/** Patch no renderHomePage para incluir o Top 10 */
const _origRenderHomePage = window.renderHomePage;
