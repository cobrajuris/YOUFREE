// ============================================================
//  moves.js  —  Master move / ability database
//
//  Each move entry shape:
//  {
//    id          : string    — unique snake_case key
//    name        : string    — display name
//    type        : string    — element (fire / water / earth / …)
//    category    : 'physical' | 'special' | 'status'
//    power       : number    — base damage (0 for status moves)
//    energyCost  : number    — energy points consumed (0–100 scale)
//    accuracy    : number    — hit chance in % (100 = always hits)
//    pp          : number    — max uses per battle
//    effect      : string | null  — status effect inflicted on target
//    effectChance: number    — % chance to trigger `effect` (0 if null)
//    selfEffect  : boolean   — true if `effect` applies to the user
//    isSuperMove : boolean   — signature / ultimate move flag
//    description : string    — flavour text shown in move inspect
//  }
//
//  Status effect IDs (used by StatusEffects.js):
//    'burn'         — lose % max HP each turn, lowers ATK slightly
//    'poison'       — lose % max HP each turn (stacks with burn)
//    'stun'         — skip next turn (1 turn)
//    'defense_up'   — self: +1 stage DEF
//    'attack_down'  — target: -1 stage ATK
//    'accuracy_down'— target: -1 stage ACC
// ============================================================

export const MOVES = {

  // ──────────────────────────────────────────
  //  FIRE TYPE
  // ──────────────────────────────────────────

  ember: {
    id: 'ember', name: 'Ember', type: 'fire', category: 'special',
    power: 40,  energyCost: 10, accuracy: 100, pp: 25,
    effect: 'burn', effectChance: 10, selfEffect: false, isSuperMove: false,
    description: 'A weak but fast burst of flame. May burn the target.',
  },

  claw_strike: {
    id: 'claw_strike', name: 'Claw Strike', type: 'fire', category: 'physical',
    power: 50,  energyCost: 15, accuracy: 95,  pp: 20,
    effect: null, effectChance: 0, selfEffect: false, isSuperMove: false,
    description: 'Slashes with claws wreathed in searing flame.',
  },

  heat_wave: {
    id: 'heat_wave', name: 'Heat Wave', type: 'fire', category: 'special',
    power: 65,  energyCost: 25, accuracy: 90,  pp: 10,
    effect: 'burn', effectChance: 25, selfEffect: false, isSuperMove: false,
    description: 'A rolling wave of scorching heat. High chance to burn.',
  },

  scorching_breath: {
    id: 'scorching_breath', name: 'Scorching Breath', type: 'fire', category: 'special',
    power: 75,  energyCost: 35, accuracy: 85,  pp: 10,
    effect: 'burn', effectChance: 40, selfEffect: false, isSuperMove: false,
    description: 'Exhales a torrent of hellfire. Often inflicts burn.',
  },

  flame_burst: {
    id: 'flame_burst', name: 'Flame Burst', type: 'fire', category: 'special',
    power: 80,  energyCost: 30, accuracy: 95,  pp: 10,
    effect: null, effectChance: 0, selfEffect: false, isSuperMove: false,
    description: 'An explosive burst of concentrated flame.',
  },

  dragon_claw: {
    id: 'dragon_claw', name: 'Dragon Claw', type: 'fire', category: 'physical',
    power: 85,  energyCost: 30, accuracy: 100, pp: 15,
    effect: null, effectChance: 0, selfEffect: false, isSuperMove: false,
    description: 'Rakes with draconic claws wrapped in devouring flame.',
  },

  inferno_jet: {
    id: 'inferno_jet', name: 'Inferno Jet', type: 'fire', category: 'special',
    power: 100, energyCost: 40, accuracy: 85,  pp: 5,
    effect: 'burn', effectChance: 50, selfEffect: false, isSuperMove: false,
    description: 'A concentrated stream of hellfire. High burn chance.',
  },

  wing_blaze: {
    id: 'wing_blaze', name: 'Wing Blaze', type: 'fire', category: 'physical',
    power: 90,  energyCost: 35, accuracy: 90,  pp: 10,
    effect: null, effectChance: 0, selfEffect: false, isSuperMove: false,
    description: 'Charges forward with wings erupting in white-hot flame.',
  },

  solar_explosion: {
    id: 'solar_explosion', name: 'Solar Explosion', type: 'fire', category: 'special',
    power: 110, energyCost: 50, accuracy: 85,  pp: 5,
    effect: 'burn', effectChance: 60, selfEffect: false, isSuperMove: false,
    description: 'Concentrates solar energy into a devastating point-blank blast.',
  },

  meteor_crash: {
    id: 'meteor_crash', name: 'Meteor Crash', type: 'fire', category: 'physical',
    power: 120, energyCost: 55, accuracy: 80,  pp: 5,
    effect: 'stun', effectChance: 20, selfEffect: false, isSuperMove: false,
    description: 'Slams the target with meteor-force impact. May stun.',
  },

  dragon_inferno: {
    id: 'dragon_inferno', name: 'Dragon Inferno', type: 'fire', category: 'special',
    power: 130, energyCost: 60, accuracy: 80,  pp: 5,
    effect: 'burn', effectChance: 70, selfEffect: false, isSuperMove: false,
    description: 'Unleashes the full draconic fire in a cataclysmic surge.',
  },

  cataclysm: {
    id: 'cataclysm', name: 'Cataclysm', type: 'fire', category: 'special',
    power: 150, energyCost: 80, accuracy: 75,  pp: 3,
    effect: 'burn', effectChance: 90, selfEffect: false, isSuperMove: true,
    description: "Infernus's signature move. Near-certain burn. Reshapes landscapes.",
  },

  // ──────────────────────────────────────────
  //  WATER TYPE
  // ──────────────────────────────────────────

  water_pulse: {
    id: 'water_pulse', name: 'Water Pulse', type: 'water', category: 'special',
    power: 40,  energyCost: 10, accuracy: 100, pp: 25,
    effect: null, effectChance: 0, selfEffect: false, isSuperMove: false,
    description: 'Fires a pulsing sphere of pressurized water.',
  },

  wing_dash: {
    id: 'wing_dash', name: 'Wing Dash', type: 'water', category: 'physical',
    power: 45,  energyCost: 12, accuracy: 100, pp: 25,
    effect: null, effectChance: 0, selfEffect: false, isSuperMove: false,
    description: 'Dashes with tremendous speed, striking with water-coated wings.',
  },

  aqua_veil: {
    id: 'aqua_veil', name: 'Aqua Veil', type: 'water', category: 'status',
    power: 0,   energyCost: 20, accuracy: 100, pp: 10,
    effect: 'defense_up', effectChance: 100, selfEffect: true, isSuperMove: false,
    description: 'Wraps in a flowing water veil, boosting its own defense.',
  },

  mist_shroud: {
    id: 'mist_shroud', name: 'Mist Shroud', type: 'water', category: 'status',
    power: 0,   energyCost: 20, accuracy: 100, pp: 10,
    effect: 'accuracy_down', effectChance: 100, selfEffect: false, isSuperMove: false,
    description: "Creates blinding mist that lowers the enemy's accuracy.",
  },

  hydro_slash: {
    id: 'hydro_slash', name: 'Hydro Slash', type: 'water', category: 'physical',
    power: 80,  energyCost: 30, accuracy: 95,  pp: 15,
    effect: null, effectChance: 0, selfEffect: false, isSuperMove: false,
    description: 'Slices with a razor-thin blade of high-pressure water.',
  },

  gale_surge: {
    id: 'gale_surge', name: 'Gale Surge', type: 'water', category: 'special',
    power: 85,  energyCost: 35, accuracy: 90,  pp: 10,
    effect: null, effectChance: 0, selfEffect: false, isSuperMove: false,
    description: 'A surging wave of water driven by fierce winds.',
  },

  tidal_wave: {
    id: 'tidal_wave', name: 'Tidal Wave', type: 'water', category: 'special',
    power: 100, energyCost: 45, accuracy: 85,  pp: 5,
    effect: null, effectChance: 0, selfEffect: false, isSuperMove: false,
    description: 'Calls forth a massive ocean wave to crash upon the foe.',
  },

  aqua_lance: {
    id: 'aqua_lance', name: 'Aqua Lance', type: 'water', category: 'physical',
    power: 95,  energyCost: 40, accuracy: 90,  pp: 10,
    effect: 'stun', effectChance: 15, selfEffect: false, isSuperMove: false,
    description: 'Thrusts a spear of pressurized water. May stun target.',
  },

  storm_strike: {
    id: 'storm_strike', name: 'Storm Strike', type: 'water', category: 'physical',
    power: 110, energyCost: 50, accuracy: 90,  pp: 5,
    effect: null, effectChance: 0, selfEffect: false, isSuperMove: false,
    description: 'A strike carrying the full force of a gathering storm.',
  },

  abyss_torrent: {
    id: 'abyss_torrent', name: 'Abyss Torrent', type: 'water', category: 'special',
    power: 125, energyCost: 55, accuracy: 85,  pp: 5,
    effect: 'poison', effectChance: 30, selfEffect: false, isSuperMove: false,
    description: 'Dark abyssal water that corrupts the target. May poison.',
  },

  hurricane: {
    id: 'hurricane', name: 'Hurricane', type: 'water', category: 'special',
    power: 135, energyCost: 60, accuracy: 80,  pp: 5,
    effect: 'stun', effectChance: 30, selfEffect: false, isSuperMove: false,
    description: 'A full-force hurricane. Wind + water combo. May stun.',
  },

  tempest_of_ages: {
    id: 'tempest_of_ages', name: 'Tempest of Ages', type: 'water', category: 'special',
    power: 150, energyCost: 80, accuracy: 75,  pp: 3,
    effect: 'stun', effectChance: 50, selfEffect: false, isSuperMove: true,
    description: "Abyssal's signature. An ancient storm that drowns continents.",
  },

  // ──────────────────────────────────────────
  //  EARTH TYPE
  // ──────────────────────────────────────────

  tackle: {
    id: 'tackle', name: 'Tackle', type: 'earth', category: 'physical',
    power: 35,  energyCost: 5,  accuracy: 100, pp: 35,
    effect: null, effectChance: 0, selfEffect: false, isSuperMove: false,
    description: 'A basic full-body tackle. Simple and reliable.',
  },

  rock_throw: {
    id: 'rock_throw', name: 'Rock Throw', type: 'earth', category: 'physical',
    power: 40,  energyCost: 10, accuracy: 95,  pp: 25,
    effect: null, effectChance: 0, selfEffect: false, isSuperMove: false,
    description: 'Hurls a chunk of rock at the target with great force.',
  },

  stone_shield: {
    id: 'stone_shield', name: 'Stone Shield', type: 'earth', category: 'status',
    power: 0,   energyCost: 20, accuracy: 100, pp: 10,
    effect: 'defense_up', effectChance: 100, selfEffect: true, isSuperMove: false,
    description: 'Forms a thick barrier of compressed stone, raising defense.',
  },

  earth_slam: {
    id: 'earth_slam', name: 'Earth Slam', type: 'earth', category: 'physical',
    power: 70,  energyCost: 25, accuracy: 90,  pp: 15,
    effect: 'stun', effectChance: 20, selfEffect: false, isSuperMove: false,
    description: 'Slams the earth, sending a shockwave. May stun the target.',
  },

  boulder_crash: {
    id: 'boulder_crash', name: 'Boulder Crash', type: 'earth', category: 'physical',
    power: 85,  energyCost: 35, accuracy: 90,  pp: 10,
    effect: null, effectChance: 0, selfEffect: false, isSuperMove: false,
    description: 'Drops a massive boulder directly onto the target.',
  },

  quake_step: {
    id: 'quake_step', name: 'Quake Step', type: 'earth', category: 'physical',
    power: 95,  energyCost: 40, accuracy: 100, pp: 10,
    effect: 'stun', effectChance: 20, selfEffect: false, isSuperMove: false,
    description: 'Each footfall sends seismic shockwaves. May stun.',
  },

  stone_armor: {
    id: 'stone_armor', name: 'Stone Armor', type: 'earth', category: 'status',
    power: 0,   energyCost: 25, accuracy: 100, pp: 8,
    effect: 'defense_up', effectChance: 100, selfEffect: true, isSuperMove: false,
    description: 'Crystallizes deep-earth minerals into impenetrable skin.',
  },

  seismic_smash: {
    id: 'seismic_smash', name: 'Seismic Smash', type: 'earth', category: 'physical',
    power: 100, energyCost: 45, accuracy: 85,  pp: 10,
    effect: 'stun', effectChance: 30, selfEffect: false, isSuperMove: false,
    description: 'A seismic strike of tremendous power. Reliable stun chance.',
  },

  titan_slam: {
    id: 'titan_slam', name: 'Titan Slam', type: 'earth', category: 'physical',
    power: 110, energyCost: 50, accuracy: 90,  pp: 5,
    effect: null, effectChance: 0, selfEffect: false, isSuperMove: false,
    description: 'A single blow carrying the weight of a collapsing mountain.',
  },

  seismic_roar: {
    id: 'seismic_roar', name: 'Seismic Roar', type: 'earth', category: 'special',
    power: 120, energyCost: 55, accuracy: 85,  pp: 5,
    effect: 'attack_down', effectChance: 40, selfEffect: false, isSuperMove: false,
    description: 'A roar that cracks the earth and shatters enemy resolve.',
  },

  mountain_crusher: {
    id: 'mountain_crusher', name: 'Mountain Crusher', type: 'earth', category: 'physical',
    power: 135, energyCost: 60, accuracy: 80,  pp: 5,
    effect: 'stun', effectChance: 40, selfEffect: false, isSuperMove: false,
    description: 'The mightiest ground-type strike. Stuns reliably.',
  },

  world_breaker: {
    id: 'world_breaker', name: 'World Breaker', type: 'earth', category: 'physical',
    power: 150, energyCost: 80, accuracy: 75,  pp: 3,
    effect: 'stun', effectChance: 60, selfEffect: false, isSuperMove: true,
    description: "Titanrock's signature. A strike that threatens to split the planet.",
  },
};
