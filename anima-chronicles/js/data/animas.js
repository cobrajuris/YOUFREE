// ============================================================
//  animas.js  —  Master Anima (monster) database
//
//  Anima entry shape:
//  {
//    id           : string      — unique camelCase / snake_case key
//    name         : string      — display name
//    title        : string      — flavour subtitle shown in inspect panel
//    type         : string      — primary element
//    secondaryType: string|null — dual-type support (optional)
//    rarity       : RARITY      — catch / encounter frequency tier
//    catchRate    : 0–255       — higher = easier to capture
//    baseXP       : number      — XP yield when defeated
//    baseStats    : {           — values at lv 1 before formula scaling
//      hp, atk, def, spatk, spdef, spd
//    }
//    evolution    : { level, into } | null
//    learnset     : [{ level, moveId }]   — sorted ascending by level
//    sprite       : {
//      color, accentColor, shape   — used by AnimaRenderer.js
//    }
//    description  : string
//  }
//
//  Stat formula (Pokémon-style, simplified):
//    HP  = floor((2 * base + IV) * lv / 100) + lv + 10
//    Stat= floor((2 * base + IV) * lv / 100) + 5
//  IVs default to 15 for all stats in the engine.
// ============================================================

export const RARITY = Object.freeze({
  COMMON:    'common',
  UNCOMMON:  'uncommon',
  RARE:      'rare',
  EPIC:      'epic',
  LEGENDARY: 'legendary',
});

/** Encounter rate weights by rarity (higher = more frequent). */
export const ENCOUNTER_WEIGHTS = Object.freeze({
  common:    60,
  uncommon:  25,
  rare:      12,
  epic:       3,
  legendary:  1,
});

export const ANIMAS = {

  // ╔══════════════════════════════════════╗
  // ║   IGNARION LINE  (Fire)             ║
  // ║   Common → Uncommon → Rare          ║
  // ╚══════════════════════════════════════╝

  ignarion: {
    id: 'ignarion',
    name: 'Ignarion',
    title: 'The Ember Hatchling',
    type: 'fire',
    secondaryType: null,
    rarity: RARITY.COMMON,
    catchRate: 200,
    baseXP: 64,
    baseStats: {
      hp:    45,
      atk:   55,
      def:   40,
      spatk: 60,
      spdef: 40,
      spd:   50,
    },
    evolution: { level: 16, into: 'pyrakon' },
    learnset: [
      { level: 1,  moveId: 'ember' },
      { level: 4,  moveId: 'claw_strike' },
      { level: 9,  moveId: 'heat_wave' },
      { level: 14, moveId: 'scorching_breath' },
    ],
    sprite: {
      color: '#ff4500',
      accentColor: '#ffd700',
      shape: 'lizard',
    },
    description:
      'A small fire lizard born in lava vents. Its body temperature spikes when ' +
      'threatened, briefly igniting the surrounding air into a protective aura.',
  },

  pyrakon: {
    id: 'pyrakon',
    name: 'Pyrakon',
    title: 'The Blazing Drake',
    type: 'fire',
    secondaryType: null,
    rarity: RARITY.UNCOMMON,
    catchRate: 90,
    baseXP: 142,
    baseStats: {
      hp:    65,
      atk:   75,
      def:   55,
      spatk: 80,
      spdef: 55,
      spd:   70,
    },
    evolution: { level: 36, into: 'infernus' },
    learnset: [
      { level: 16, moveId: 'flame_burst' },
      { level: 20, moveId: 'dragon_claw' },
      { level: 28, moveId: 'inferno_jet' },
      { level: 33, moveId: 'wing_blaze' },
    ],
    sprite: {
      color: '#cc2200',
      accentColor: '#ff8c00',
      shape: 'drake',
    },
    description:
      'Having shed its juvenile scales, Pyrakon\'s wings now radiate enough heat ' +
      'to ignite treetops during a full-speed dive.',
  },

  infernus: {
    id: 'infernus',
    name: 'Infernus',
    title: 'The Eternal Inferno',
    type: 'fire',
    secondaryType: null,
    rarity: RARITY.RARE,
    catchRate: 30,
    baseXP: 280,
    baseStats: {
      hp:    90,
      atk:   105,
      def:   75,
      spatk: 110,
      spdef: 75,
      spd:   90,
    },
    evolution: null,
    learnset: [
      { level: 36, moveId: 'solar_explosion' },
      { level: 42, moveId: 'meteor_crash' },
      { level: 50, moveId: 'dragon_inferno' },
      { level: 58, moveId: 'cataclysm' },
    ],
    sprite: {
      color: '#8b0000',
      accentColor: '#ff4500',
      shape: 'dragon',
    },
    description:
      'Infernus radiates heat intense enough to melt steel at 30 meters. ' +
      'Ancient texts describe its roar as the sound of the sun itself fracturing.',
  },

  // ╔══════════════════════════════════════╗
  // ║   AQUILIS LINE  (Water / Air)       ║
  // ║   Common → Uncommon → Rare          ║
  // ╚══════════════════════════════════════╝

  aquilis: {
    id: 'aquilis',
    name: 'Aquilis',
    title: 'The Creek Fledgling',
    type: 'water',
    secondaryType: null,
    rarity: RARITY.COMMON,
    catchRate: 200,
    baseXP: 62,
    baseStats: {
      hp:    50,
      atk:   45,
      def:   55,
      spatk: 55,
      spdef: 55,
      spd:   60,
    },
    evolution: { level: 16, into: 'torrentis' },
    learnset: [
      { level: 1,  moveId: 'water_pulse' },
      { level: 5,  moveId: 'wing_dash' },
      { level: 10, moveId: 'aqua_veil' },
      { level: 15, moveId: 'mist_shroud' },
    ],
    sprite: {
      color: '#1e90ff',
      accentColor: '#00ced1',
      shape: 'bird',
    },
    description:
      'A small aquatic bird that glides across river surfaces at remarkable speed. ' +
      'Its feathers channel water into concentrated blasts when threatened.',
  },

  torrentis: {
    id: 'torrentis',
    name: 'Torrentis',
    title: 'The Cascade Hawk',
    type: 'water',
    secondaryType: 'air',
    rarity: RARITY.UNCOMMON,
    catchRate: 90,
    baseXP: 148,
    baseStats: {
      hp:    72,
      atk:   65,
      def:   72,
      spatk: 75,
      spdef: 72,
      spd:   80,
    },
    evolution: { level: 36, into: 'abyssal' },
    learnset: [
      { level: 16, moveId: 'hydro_slash' },
      { level: 22, moveId: 'gale_surge' },
      { level: 30, moveId: 'tidal_wave' },
      { level: 35, moveId: 'aqua_lance' },
    ],
    sprite: {
      color: '#0066cc',
      accentColor: '#87ceeb',
      shape: 'raptor',
    },
    description:
      'Torrentis commands both wind and water simultaneously. Its wings can generate ' +
      'localized storm cells powerful enough to flood a small valley.',
  },

  abyssal: {
    id: 'abyssal',
    name: 'Abyssal',
    title: 'The Storm Phoenix',
    type: 'water',
    secondaryType: 'air',
    rarity: RARITY.RARE,
    catchRate: 30,
    baseXP: 290,
    baseStats: {
      hp:    85,
      atk:   85,
      def:   90,
      spatk: 100,
      spdef: 90,
      spd:   105,
    },
    evolution: null,
    learnset: [
      { level: 36, moveId: 'storm_strike' },
      { level: 44, moveId: 'abyss_torrent' },
      { level: 52, moveId: 'hurricane' },
      { level: 60, moveId: 'tempest_of_ages' },
    ],
    sprite: {
      color: '#003580',
      accentColor: '#00bfff',
      shape: 'phoenix',
    },
    description:
      'A mythic avian titan born from deep-ocean vortexes. Its wingspan can blot out ' +
      'the sky, and each wingbeat summons rolling thunder.',
  },

  // ╔══════════════════════════════════════╗
  // ║   TERRAVYN LINE  (Earth)            ║
  // ║   Common → Uncommon → Rare          ║
  // ╚══════════════════════════════════════╝

  terravyn: {
    id: 'terravyn',
    name: 'Terravyn',
    title: 'The Pebble Pup',
    type: 'earth',
    secondaryType: null,
    rarity: RARITY.COMMON,
    catchRate: 200,
    baseXP: 60,
    baseStats: {
      hp:    60,
      atk:   60,
      def:   70,
      spatk: 35,
      spdef: 60,
      spd:   35,
    },
    evolution: { level: 18, into: 'geokin' },
    learnset: [
      { level: 1,  moveId: 'tackle' },
      { level: 3,  moveId: 'rock_throw' },
      { level: 8,  moveId: 'stone_shield' },
      { level: 15, moveId: 'earth_slam' },
    ],
    sprite: {
      color: '#8b6914',
      accentColor: '#a0522d',
      shape: 'quadruped',
    },
    description:
      'A young rock-dog with a hide of compressed granite. ' +
      'Terravyn instinctively absorbs minerals from the ground, slowly hardening its body.',
  },

  geokin: {
    id: 'geokin',
    name: 'Geokin',
    title: 'The Stone Wolf',
    type: 'earth',
    secondaryType: null,
    rarity: RARITY.UNCOMMON,
    catchRate: 90,
    baseXP: 152,
    baseStats: {
      hp:    85,
      atk:   80,
      def:   95,
      spatk: 50,
      spdef: 80,
      spd:   48,
    },
    evolution: { level: 38, into: 'titanrock' },
    learnset: [
      { level: 18, moveId: 'boulder_crash' },
      { level: 25, moveId: 'quake_step' },
      { level: 33, moveId: 'stone_armor' },
      { level: 38, moveId: 'seismic_smash' },
    ],
    sprite: {
      color: '#6b4226',
      accentColor: '#8b7355',
      shape: 'wolf',
    },
    description:
      'A powerfully-built earth wolf whose howl registers as a 3.5-magnitude tremor. ' +
      'Its claws can tear through bedrock.',
  },

  titanrock: {
    id: 'titanrock',
    name: 'Titanrock',
    title: 'The Mountain Sovereign',
    type: 'earth',
    secondaryType: null,
    rarity: RARITY.RARE,
    catchRate: 30,
    baseXP: 305,
    baseStats: {
      hp:    120,
      atk:   110,
      def:   135,
      spatk: 65,
      spdef: 105,
      spd:   58,
    },
    evolution: null,
    learnset: [
      { level: 38, moveId: 'titan_slam' },
      { level: 45, moveId: 'seismic_roar' },
      { level: 54, moveId: 'mountain_crusher' },
      { level: 62, moveId: 'world_breaker' },
    ],
    sprite: {
      color: '#3d2b1f',
      accentColor: '#6b4226',
      shape: 'titan',
    },
    description:
      'An ancient geological titan whose footsteps reshape the terrain. ' +
      'Geologists believe dormant Titanrocks are sometimes mistaken for small mountains.',
  },
};

// ──────────────────────────────────────────
//  Helper utilities (used by the engine)
// ──────────────────────────────────────────

/**
 * Calculates an Anima's derived stats at a given level.
 * Uses the standard formula:  HP  = floor((2*base + IV) * lv / 100) + lv + 10
 *                             Stat= floor((2*base + IV) * lv / 100) + 5
 * @param {object} animaData  - Entry from ANIMAS
 * @param {number} level      - Target level (1–100)
 * @param {number} [iv=15]    - Individual Value (0–31)
 * @returns {{ hp, atk, def, spatk, spdef, spd }}
 */
export function calcStats(animaData, level, iv = 15) {
  const b = animaData.baseStats;
  const lv = level;
  return {
    hp:    Math.floor((2 * b.hp    + iv) * lv / 100) + lv + 10,
    atk:   Math.floor((2 * b.atk   + iv) * lv / 100) + 5,
    def:   Math.floor((2 * b.def   + iv) * lv / 100) + 5,
    spatk: Math.floor((2 * b.spatk + iv) * lv / 100) + 5,
    spdef: Math.floor((2 * b.spdef + iv) * lv / 100) + 5,
    spd:   Math.floor((2 * b.spd   + iv) * lv / 100) + 5,
  };
}

/**
 * Returns the move IDs an Anima has learned at or before `level`.
 * Caps at the last 4 moves learned (most recent = active set).
 * @param {object} animaData
 * @param {number} level
 * @returns {string[]}
 */
export function getLearnedMoves(animaData, level) {
  return animaData.learnset
    .filter(entry => entry.level <= level)
    .slice(-4)
    .map(entry => entry.moveId);
}

/**
 * XP required to reach `level` — Medium-Fast growth curve.
 * Formula: floor(0.8 * level^3)
 * @param {number} level
 * @returns {number}
 */
export function xpForLevel(level) {
  return Math.floor(0.8 * Math.pow(level, 3));
}

/**
 * XP awarded when defeating an Anima.
 * Wild Animas give less than trained ones (trainer bonus = ×1.5).
 * @param {object} animaData     - Defeated Anima's data
 * @param {number} defeatedLevel - Level of the defeated Anima
 * @param {boolean} isWild       - true = wild encounter
 * @returns {number}
 */
export function calcXPGain(animaData, defeatedLevel, isWild = true) {
  const trainerMod = isWild ? 1.0 : 1.5;
  return Math.floor((animaData.baseXP * defeatedLevel * trainerMod) / 7);
}

/**
 * Returns the Anima data for the next evolution, or null if at final form.
 * @param {object} animaData
 * @returns {object|null}
 */
export function getEvolution(animaData) {
  if (!animaData.evolution) return null;
  return ANIMAS[animaData.evolution.into] ?? null;
}
