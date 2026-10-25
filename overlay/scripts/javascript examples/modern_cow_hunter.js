// Cow hunter written in modern JavaScript: a class, async/await, destructuring, spread and template strings.
// It opens the pen gate if it's shut, fights the nearest free cow, eats when low, loots hides from its own kills and buries the bones.
class Hunter {
  #kills = 0;

  constructor({ target = "Cow", eatBelow = 50, food = "Lobster", loot = ["Cowhide"] } = {}) {
    Object.assign(this, { target, eatBelow, food, loot });
  }

  get kills() { return this.#kills; }

  async fight() {
    const cow = get_nearest_monster({ name: this.target });
    if (!cow) { await sleep(1200); return; }
    const { x, y, name } = cow;
    if (attack(cow) === "door") { await until_idle(20000); return; }   // a gate was in the way: it's open now, try again
    const fighting = await wait_until(() => get_targeted_monster(), 6000).catch(() => null);
    if (!fighting) return;                                  // someone else got it first
    await wait_until(() => !get_targeted_monster() && !in_combat(), 90000);
    this.#kills++;
    set_message(`kills: ${this.#kills}`);
    game_log(`killed ${name} #${this.#kills} at ${x},${y}`);
    await sleep(1800);                                      // let the drop appear
    if (character.free_slots === 0) set_message(`kills: ${this.#kills} (inventory full)`);
    else for (let i = 0; i < 3 && loot([...this.loot, "Bones"], { x, y, radius: 1 }); i++) await until_idle(10000);
    if (has("Bones")) { bury(); await until_idle(6000); }
  }
}

const hunter = new Hunter({ eatBelow: 60 });

(async () => {
  game_log(`hunting ${hunter.target}s`);
  while (true) {
    if (hp_percent() < hunter.eatBelow && has(hunter.food)) {
      eat(hunter.food);
      await sleep(1800);
    }
    await hunter.fight();
  }
})();
