// ============================================================
//  TypeChart.js  —  Elemental effectiveness matrix
//  Six elements: Fire · Water · Earth · Air · Light · Shadow
// ============================================================

export const ELEMENTS = Object.freeze({
  FIRE:   'fire',
  WATER:  'water',
  EARTH:  'earth',
  AIR:    'air',
  LIGHT:  'light',
  SHADOW: 'shadow',
});

/** Visual identity for each element (used by the UI layer). */
export const ELEMENT_COLORS = Object.freeze({
  fire:   { primary: '#ff4500', secondary: '#ffd700', glow: '#ff6622' },
  water:  { primary: '#1e90ff', secondary: '#00ced1', glow: '#00bfff' },
  earth:  { primary: '#8b6914', secondary: '#a0522d', glow: '#cd853f' },
  air:    { primary: '#87ceeb', secondary: '#e0f4ff', glow: '#b0e0e6' },
  light:  { primary: '#ffd700', secondary: '#fff8dc', glow: '#fffacd' },
  shadow: { primary: '#6a0dad', secondary: '#2d0057', glow: '#9b30ff' },
});

export const ELEMENT_LABELS = Object.freeze({
  fire:   'Fire',
  water:  'Water',
  earth:  'Earth',
  air:    'Air',
  light:  'Light',
  shadow: 'Shadow',
});

// ────────────────────────────────────────────────────────────
//  Damage multiplier: TYPE_CHART[attackType][defenderType]
//  2.0  → Super Effective       ("It's devastating!")
//  1.0  → Normal effectiveness
//  0.5  → Not Very Effective
//  0.0  → Immune                ("It had no effect...")
// ────────────────────────────────────────────────────────────
export const TYPE_CHART = Object.freeze({
  //        fire   water  earth  air    light  shadow
  fire:   { fire: 0.5, water: 0.5, earth: 2.0, air: 2.0, light: 1.0, shadow: 0.5 },
  water:  { fire: 2.0, water: 0.5, earth: 0.5, air: 1.0, light: 2.0, shadow: 0.5 },
  earth:  { fire: 0.5, water: 2.0, earth: 0.5, air: 0.5, light: 0.5, shadow: 1.0 },
  air:    { fire: 0.5, water: 1.0, earth: 2.0, air: 0.5, light: 1.0, shadow: 2.0 },
  light:  { fire: 1.0, water: 0.5, earth: 2.0, air: 1.0, light: 0.5, shadow: 2.0 },
  shadow: { fire: 2.0, water: 2.0, earth: 1.0, air: 0.5, light: 0.5, shadow: 0.5 },
});

/**
 * Returns the combined damage multiplier when an attack of `attackType`
 * hits a defender with one or two types.
 *
 * @param {string}          attackType    - Element of the move being used.
 * @param {string|string[]} defenderTypes - One or two element strings of the target.
 * @returns {number}  Multiplier (e.g. 2.0, 0.5, 4.0 for dual-weakness)
 */
export function getEffectiveness(attackType, defenderTypes) {
  const types = Array.isArray(defenderTypes) ? defenderTypes : [defenderTypes];
  return types
    .filter(Boolean)
    .reduce((mult, dt) => mult * (TYPE_CHART[attackType]?.[dt] ?? 1.0), 1.0);
}

/**
 * Returns a battle-log entry for the effectiveness, or null for neutral.
 * @param {number} multiplier
 * @returns {{ text: string, emphasis: string } | null}
 */
export function effectivenessLabel(multiplier) {
  if (multiplier === 0)   return { text: "It had no effect...",            emphasis: 'immune'  };
  if (multiplier >= 4)    return { text: "It's extraordinarily effective!", emphasis: 'ultra'   };
  if (multiplier >= 2)    return { text: "It's super effective!",           emphasis: 'super'   };
  if (multiplier <= 0.25) return { text: "It's barely effective...",        emphasis: 'weak'    };
  if (multiplier <  1)    return { text: "It's not very effective...",      emphasis: 'resist'  };
  return null;
}
