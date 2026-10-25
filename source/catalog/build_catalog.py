#!/usr/bin/env python3
"""Builds catalog.json, the game catalogue behind the block editor, from the server's own data.

Input: raw/ (written by the server-side `catalog DIR` command plus the server's config files).
Output: catalog.json (shipped in codeedition/) and coverage.md (what each skill has).

Nothing here is typed in by hand except the friendly names of a few groups; every level,
item, product, tool and place comes from the game data.
"""
import json, math, os, re, sys, collections, datetime

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "raw")
SRC = "/home/claude/work/upstream/server-src/Server/src/main/content"

SKILLS = ["attack", "defence", "strength", "hitpoints", "ranged", "prayer", "magic", "cooking", "woodcutting",
          "fletching", "fishing", "firemaking", "crafting", "smithing", "mining", "herblore", "agility", "thieving",
          "slayer", "farming", "runecrafting", "hunter", "construction", "summoning"]


def load(name):
    with open(os.path.join(RAW, name), encoding="utf-8") as f:
        return json.load(f)


defs = load("defs.json")
enums = load("enums.json")
labels = load("labels.json")
objmap = load("map.json")
spawns = load("npc_spawns.json")
shops_raw = load("shops.json")
item_cfg = load("item_configs.json")
npc_cfg = load("npc_configs.json")


def E(name):
    return enums.get("content." + name) or enums.get(name) or []


# ---------------------------------------------------------------- names
ITEM = {i["id"]: i for i in defs["items"]}
NPC = {n["id"]: n for n in defs["npcs"]}
OBJ = {o["id"]: o for o in defs["objects"]}


def iname(v):
    """item name from an id, an {item,amount,name} or a list of either"""
    if v is None:
        return None
    if isinstance(v, dict):
        return v.get("name") or iname(v.get("item"))
    if isinstance(v, int):
        it = ITEM.get(v)
        return it["name"] if it else None
    return str(v)


def items_of(v):
    """[{name, amount}] from an Item, an id, or a list"""
    if v is None:
        return []
    if isinstance(v, list):
        out = []
        for x in v:
            out += items_of(x)
        return out
    if isinstance(v, dict) and "item" in v:
        return [{"name": iname(v), "amount": v.get("amount", 1)}]
    if isinstance(v, int):
        n = iname(v)
        return [{"name": n, "amount": 1}] if n else []
    return []


def nice(enum_name):
    s = enum_name.replace("_", " ").lower()
    return s[:1].upper() + s[1:]


def obj_name(oid):
    o = OBJ.get(oid)
    if not o:
        return ""
    if o["name"]:
        return o["name"]
    for k in o.get("children") or []:   # farming patches and other objects that change with a varbit
        if k and k > 0 and OBJ.get(k, {}).get("name"):
            return OBJ[k]["name"]
    return ""


# ---------------------------------------------------------------- places (world map labels)
PLACES = []
for l in labels:
    name = re.sub(r"<br>", " ", l["name"]).strip()
    name = re.sub(r"<[^>]+>", "", name)
    if not name:
        continue
    PLACES.append({"name": name, "x": l["x"], "y": l["y"], "z": 0, "kind": "area", "size": l["size"]})


# the same name on two map labels (two Bandit Camps, several Ruins): say which one by the nearest town
_dups = collections.Counter(p["name"].lower() for p in PLACES)
for p in PLACES:
    if _dups[p["name"].lower()] > 1:
        towns = [q for q in PLACES if q is not p and q.get("size", 0) >= 1 and q["name"].lower() != p["name"].lower()]
        t = min(towns, key=lambda q: math.hypot(q["x"] - p["x"], q["y"] - p["y"]))
        p["name"] = f"{p['name']} near {t['name']}"


def nearest_place(x, y, z=0, maxd=90):
    """the world map label a point belongs to; big town labels win over small ones close by"""
    best, bd = None, 1e9
    # dungeons sit far north on the map (y > 6400); fold them back roughly onto the surface for naming
    yy = y - 6400 if y > 6400 else y
    for p in PLACES:
        if p["kind"] != "area":
            continue
        d = math.hypot(p["x"] - x, p["y"] - yy)
        score = d - 35 * p.get("size", 0)       # size 1 and 2 are towns and kingdoms on the world map
        if p.get("size", 0) >= 2:
            score = d + 60                      # a kingdom label only when nothing closer has a name
        if d <= maxd and score < bd:
            best, bd = p, score
    if best is None:
        return None
    return best["name"] + (" dungeon" if y > 6400 else "")


def compass(x, y, px, py):
    dx, dy = x - px, y - py
    if abs(dx) > abs(dy):
        return "east" if dx > 0 else "west"
    return "north" if dy > 0 else "south"


def cluster(points, radius=14):
    """group x,y,z points that sit close together; returns [(cx, cy, z, n)]"""
    groups = []
    for x, y, z in points:
        for g in groups:
            if g[2] == z and abs(g[0] / g[3] - x) <= radius and abs(g[1] / g[3] - y) <= radius:
                g[0] += x; g[1] += y; g[3] += 1
                break
        else:
            groups.append([x, y, z, 1])
    return [(round(g[0] / g[3]), round(g[1] / g[3]), g[2], g[3]) for g in groups]


def obj_points(ids):
    pts = []
    for oid in ids:
        m = objmap.get(str(oid))
        if not m:
            continue
        a = m["at"]
        pts += [(a[i], a[i + 1], a[i + 2]) for i in range(0, len(a), 3)]
    return pts


OBJ_BY_NAME = collections.defaultdict(list)
for k in objmap:
    n = obj_name(int(k))
    if n:
        OBJ_BY_NAME[n].append(int(k))

# useful stations become named places: "Draynor Village bank", "Falador furnace"...
STATIONS = {"bank": ["Bank booth", "Bank chest", "Bank deposit box"], "furnace": ["Furnace"], "anvil": ["Anvil"],
            "range": ["Range", "Cooking range", "Cooking pot"], "spinning wheel": ["Spinning wheel"], "loom": ["Loom"],
            "pottery oven": ["Pottery Oven", "Pottery oven"], "potter's wheel": ["Potter's Wheel", "Potter's wheel"],
            "altar": ["Altar", "Chaos altar"], "fountain": ["Fountain"], "sink": ["Sink"], "well": ["Well"],
            "water pump": ["Waterpump", "Water pump"], "dairy churn": ["Dairy churn"], "tannery": [],
            "obelisk": ["Obelisk"]}
taken = set()
by_base = {}
for kind, names in STATIONS.items():
    pts = obj_points([i for n in names for i in OBJ_BY_NAME.get(n, [])])
    for (x, y, z, n) in cluster(pts, 18 if kind == "bank" else 10):
        area = nearest_place(x, y, z)
        if not area:
            continue
        name = f"{area} {kind}"
        if name in by_base:        # a second bank in the same town: Varrock west bank, Varrock east bank
            lab = next(p for p in PLACES if p["kind"] == "area" and area.startswith(p["name"]))
            first = by_base[name]
            if first["name"] == name:
                first["name"] = f"{area} {compass(first['x'], first['y'], lab['x'], lab['y'])} {kind}"
                taken.add(first["name"])
            base, name = name, f"{area} {compass(x, y, lab['x'], lab['y'])} {kind}"
            if name in taken:
                continue
        else:
            base = name
        taken.add(name)
        PLACES.append({"name": name, "x": x, "y": y, "z": z, "kind": kind})
        by_base.setdefault(base, PLACES[-1])

# the hand-tuned spots players already know from v2 stay, so old names keep working
LEGACY = {"Lumbridge": (3222, 3218, 0), "Lumbridge Cows": (3257, 3270, 0), "Lumbridge Chickens": (3232, 3295, 0),
          "Draynor Bank": (3093, 3243, 0), "Draynor Willows": (3087, 3235, 0), "Varrock West Bank": (3185, 3436, 0),
          "Varrock East Bank": (3253, 3420, 0), "Varrock Square": (3213, 3428, 0), "Barbarian Village": (3082, 3420, 0),
          "Falador East Bank": (3013, 3355, 0), "Al Kharid Mine": (3299, 3300, 0), "Grand Exchange": (3165, 3487, 0),
          # copper, tin (and iron) rocks for the miner example
          "Varrock East Mine": (3285, 3365, 0), "Lumbridge Swamp Mine": (3227, 3150, 0)}
for n, (x, y, z) in LEGACY.items():
    if not any(p["name"].lower() == n.lower() for p in PLACES):
        PLACES.append({"name": n, "x": x, "y": y, "z": z, "kind": "spot"})


def where(points, limit=6):
    """the main areas a set of points falls in, most first"""
    c = collections.Counter()
    for x, y, z in points:
        a = nearest_place(x, y, z)
        if a:
            c[a] += 1
    return [a for a, _ in c.most_common(limit)]


# ---------------------------------------------------------------- items
ITEMS = {}
for it in defs["items"]:
    if it["noted"] or not it["name"]:
        continue
    n = it["name"]
    if n not in ITEMS:
        ITEMS[n] = {"id": it["id"], "stackable": it["stackable"], "options": [o for o in it["options"] if o]}
REQ = {}
for c in item_cfg:
    r = c.get("requirements")
    if r:
        reqs = re.findall(r"\{(\d+),(\d+)\}", r)
        REQ[int(c["id"])] = {SKILLS[int(s)]: int(l) for s, l in reqs if int(s) < len(SKILLS)}
for n, v in ITEMS.items():
    if v["id"] in REQ:
        v["requires"] = REQ[v["id"]]

# ---------------------------------------------------------------- NPCs and monsters
SPAWN_PTS = collections.defaultdict(list)
for s in spawns:
    nid = int(s["npc_id"])
    for m in re.findall(r"\{(\d+),(\d+),(\d+)", s["loc_data"]):
        SPAWN_PTS[nid].append(tuple(map(int, m)))
CFG = {int(c["id"]): c for c in npc_cfg}

monsters, people = {}, {}
for n in defs["npcs"]:
    nid, name = n["id"], n["name"]
    opts = [o for o in n["options"] if o]
    if not opts and not SPAWN_PTS.get(nid):
        continue
    pts = SPAWN_PTS.get(nid, [])
    target = monsters if "Attack" in opts else people
    e = target.setdefault(name, {"ids": [], "options": [], "spawns": 0, "_pts": []})
    e["ids"].append(nid)
    for o in opts:
        if o not in e["options"]:
            e["options"].append(o)
    e["spawns"] += len(pts)
    e["_pts"] += pts
    if target is monsters:
        e.setdefault("levels", []).append(n["combat"])
        c = CFG.get(nid, {})
        if c.get("lifepoints"):
            e["hp"] = max(e.get("hp", 0), int(c["lifepoints"]))
        if c.get("aggressive") == "true":
            e["aggressive"] = True
        if c.get("slayer_task"):
            e["slayer"] = True
for coll in (monsters, people):
    for name in list(coll):
        e = coll[name]
        pts = e.pop("_pts")
        e["areas"] = where(pts)
        # where to find them, for Walk To: the busiest spawn spots first
        e["at"] = [[x, y, z] for x, y, z, n in sorted(cluster(pts, 12), key=lambda c: -c[3])][:10]
        if "levels" in e:
            lv = sorted(set(x for x in e.pop("levels") if x > 0))
            e["combat"] = [lv[0], lv[-1]] if lv else [0, 0]
        if not e["spawns"] and coll is people:
            del coll[name]          # quest-only and cutscene NPCs nobody can find

# ---------------------------------------------------------------- objects
objects = {}
for name, ids in OBJ_BY_NAME.items():
    opts = []
    for i in ids:
        for o in OBJ[i]["options"]:
            if o and o not in opts and o != "Examine":
                opts.append(o)
    pts = obj_points(ids)
    objects[name] = {"ids": sorted(ids)[:60], "options": opts, "count": len(pts), "areas": where(pts, 5)}

# ---------------------------------------------------------------- skills
S = {}

S["woodcutting"] = {"trees": []}
seen = set()
for t in E("global.skill.gather.woodcutting.WoodcuttingNode"):
    tree = obj_name(t["full"]) or nice(t["_name"].rsplit("_", 1)[0])
    log = iname(t["reward"])
    key = (tree, log)
    if key in seen or not log:
        continue
    seen.add(key)
    S["woodcutting"]["trees"].append({"tree": tree, "level": t["level"], "xp": t["experience"], "gives": log,
                                      "farming": t.get("farming", False)})
S["woodcutting"]["axes"] = [{"name": iname(t["id"]), "level": t["level"]} for t in E("data.skill.SkillingTool") if "AXE" in t["_name"] and "PICK" not in t["_name"]]

S["mining"] = {"rocks": []}
seen = set()
for r in E("global.skill.gather.mining.MiningNode"):
    ore = iname(r["reward"])
    if not ore or ore in seen:
        continue
    seen.add(ore)
    S["mining"]["rocks"].append({"rock": obj_name(r["full"]) or "Rocks", "ore": ore, "level": r["level"], "xp": r["experience"]})
S["mining"]["pickaxes"] = [{"name": iname(t["id"]), "level": t["level"]} for t in E("data.skill.SkillingTool") if "PICKAXE" in t["_name"]]

fish = {f["_name"]: f for f in E("global.skill.fishing.Fish")}
spot_npcs = {}
for sp in E("global.skill.fishing.FishingSpot"):
    for o in sp["options"]:
        spot_npcs.setdefault(o if isinstance(o, str) else o.get("_name", str(o)), []).append(sp["_name"])
S["fishing"] = {"methods": []}
for o in E("global.skill.fishing.FishingOption"):
    S["fishing"]["methods"].append({
        "method": nice(o["_name"]), "option": o["option"].capitalize(), "level": o["level"], "tool": iname(o["tool"]),
        "bait": [x["name"] for x in items_of(o["bait"])] if o["bait"] else [],
        "fish": [{"name": iname(fish[f]["id"]), "level": fish[f]["level"], "xp": fish[f]["experience"]} for f in o["fish"] if f in fish]})

S["firemaking"] = {"logs": [{"log": iname(l["logId"]), "level": l["level"], "xp": l["xp"]} for l in E("global.skill.firemaking.Log")]}

S["cooking"] = {"food": [{"raw": iname(c["raw"]), "cooked": iname(c["cooked"]), "level": c["level"], "xp": c.get("experience")}
                         for c in E("global.skill.cooking.CookableItems") if iname(c["raw"])]}
S["cooking"]["combine"] = []
for k, label in [("global.skill.cooking.recipe.PieListener$PieProduct", "pie"), ("global.skill.cooking.recipe.PizzaListener$PizzaProduct", "pizza"),
                 ("global.skill.cooking.recipe.PotatoListener$PotatoProduct", "potato"), ("global.skill.cooking.recipe.StewListener$StewProduct", "stew"),
                 ("global.skill.cooking.recipe.ToppingListener$ToppingProduct", "topping")]:
    for p in E(k):
        S["cooking"]["combine"].append({"use": iname(p.get("added")), "on": iname(p.get("base")) or "Baked potato",
                                        "makes": iname(p["product"]), "level": p.get("minimumLevel", 1), "xp": p.get("experience")})

S["prayer"] = {"bones": [{"bones": iname(b["itemId"]), "xp": b["experience"]} for b in E("global.skill.prayer.Bones")]}

S["herblore"] = {
    "clean": [{"herb": iname(h["herb"]), "makes": iname(h["product"]), "level": h["level"], "xp": h["experience"]} for h in E("global.skill.herblore.Herbs")],
    "unfinished": [{"use": iname(u["ingredient"]), "on": iname(u["base"]), "makes": iname(u["potion"]), "level": u["level"]} for u in E("global.skill.herblore.UnfinishedPotion")],
    "grind": [{"item": x["name"], "makes": iname(g["product"])} for g in E("global.skill.herblore.GrindingItem") for x in items_of(g["items"])],
    "tar": [{"herb": iname(t["ingredient"]), "makes": iname(t["tar"]), "level": t["level"], "xp": t["experience"]} for t in E("global.skill.herblore.Tars")],
}
unf = {u["_name"]: iname(u["potion"]) for u in E("global.skill.herblore.UnfinishedPotion")}
S["herblore"]["potions"] = [{"use": iname(p["ingredient"]), "on": unf.get(p["unfinished"], p["unfinished"]), "makes": iname(p["potion"]),
                             "level": p["level"], "xp": p["experience"]} for p in E("global.skill.herblore.FinishedPotion")]

S["fletching"] = {
    "cut": [{"log": iname(l["logItemId"]), "makes": iname(l["finishedItemId"]), "level": l["level"], "xp": l["experience"]} for l in E("global.skill.fletching.log.LogCraftInfo")],
    "string": [{"use": "Crossbow string" if s["isCrossbow"] else "Bow string", "on": iname(s["unstrungItemId"]), "makes": iname(s["strungItemId"]), "level": s["level"], "xp": s["experience"]} for s in E("global.skill.fletching.stringing.StringableCraftInfo")],
    "arrows": [{"use": iname(a["tipItemId"]), "on": "Headless arrow", "makes": iname(a["arrowItemId"]), "level": a["level"], "xp": a["experience"]} for a in E("global.skill.fletching.arrow.ArrowCraftInfo")],
    "darts": [{"use": "Feather", "on": iname(d["tipItemId"]), "makes": iname(d["dartItemId"]), "level": d["level"], "xp": d["experience"]} for d in E("global.skill.fletching.darts.DartCraftInfo")],
    "bolts": [{"use": "Feather", "on": iname(b["unfinishedBoltItemId"]), "makes": iname(b["finishedItemId"]), "level": b["level"], "xp": b["experience"]} for b in E("global.skill.fletching.bolts.BoltCraftInfo")],
    "crossbows": [{"use": iname(c["limbItemId"]), "on": iname(c["stockItemId"]), "makes": iname(c["unstrungCrossbowItemId"]), "level": c["level"], "xp": c["experience"]} for c in E("global.skill.fletching.crossbow.UnfinishedCrossbowCraftInfo")],
    "gem bolts": [{"use": iname(g["tipItemId"]), "on": iname(g["untippedBoltItemId"]), "makes": iname(g["tippedBoltItemId"]), "level": g["level"], "xp": g["experience"]} for g in E("global.skill.fletching.gem.GemBoltsCraftInfo")],
}

C = S["crafting"] = {}
C["gems"] = [{"use": "Chisel", "on": iname(g["uncut"]), "makes": iname(g["gem"]), "level": g["level"], "xp": g["exp"]} for g in E("global.skill.crafting.gem.Gems")]
C["spinning"] = [{"use": iname(s["need"]), "on": "Spinning wheel", "makes": iname(s["product"]), "level": s["level"], "xp": s["exp"]} for s in E("global.skill.crafting.spinning.SpinningItem")]
C["pottery"] = [{"use": "Soft clay", "on": "Potter's wheel", "makes": iname(p["unfinished"]), "then": iname(p["product"]), "level": p["level"], "xp": p["exp"]} for p in E("global.skill.crafting.pottery.PotteryItem")]
C["glass"] = [{"use": "Glassblowing pipe", "on": "Molten glass", "makes": iname(g["producedItemId"]), "level": g["minimumLevel"], "xp": g["experience"]} for g in E("global.skill.crafting.glass.GlassProduct")]
C["leather"] = [{"use": "Needle", "on": "Leather", "makes": iname(s["product"]), "level": s["level"], "xp": s["experience"]} for s in E("global.skill.crafting.armour.LeatherCrafting$SoftLeather")]
C["dragonhide"] = [{"use": "Needle", "on": iname(d["leather"]), "amount": d["amount"], "makes": iname(d["product"]), "level": d["level"], "xp": d["experience"]} for d in E("global.skill.crafting.armour.LeatherCrafting$DragonHide")]
C["snakeskin"] = [{"use": "Needle", "on": "Snakeskin", "amount": s["requiredAmount"], "makes": iname(s["product"]), "level": s["level"], "xp": s["experience"]} for s in E("global.skill.crafting.armour.SnakeSkin")]
C["jewellery"] = [{"use": iname(j["items"][-1]) if j["items"] else "Gold bar", "on": "Furnace", "needs": [iname(i) for i in j["items"]], "makes": iname(j["sendItem"]), "level": j["level"], "xp": j["experience"]} for j in E("global.skill.crafting.jewellery.JewelleryCrafting$JewelleryItem")]
C["silver"] = [{"use": "Silver bar", "on": "Furnace", "mould": iname(s["requiredItemId"]), "makes": iname(s["producedItemId"]), "level": s["minimumLevel"], "xp": s["xpReward"]} for s in E("global.skill.crafting.silver.SilverProduct")]
C["tanning"] = [{"hide": iname(t["item"]), "makes": iname(t["product"])} for t in E("global.skill.crafting.TanningProduct")]
C["weaving"] = [{"use": iname(w["required"]), "on": "Loom", "makes": iname(w["product"]), "level": w["level"], "xp": w["experience"]} for w in E("global.skill.crafting.WeaveOptionPlugin$WeavingItem")]
C["battlestaves"] = [{"use": iname(b["requiredOrbItemId"]), "on": "Battlestaff", "makes": iname(b["producedItemId"]), "level": b["minimumLevel"], "xp": b["experience"]} for b in E("global.skill.crafting.BattlestaffListener$BattlestaffProduct")]
C["lights"] = [{"empty": iname(l["emptyID"]), "full": iname(l["fullID"]), "lit": iname(l["litID"]), "level": l["levelRequired"]} for l in E("global.skill.crafting.lightsources.LightSources")]

bartype = {b["_name"]: b for b in E("global.skill.smithing.BarType")}
stype = {s["_name"]: s for s in E("global.skill.smithing.SmithingType")}
S["smithing"] = {
    "smelt": [{"ores": items_of(b["ores"]), "makes": iname(b["product"]), "level": b["level"], "xp": b["experience"]} for b in E("global.skill.smithing.smelting.Bar")],
    "smith": [{"bar": iname(bartype[b["Btype"]]["bar"]) if b["Btype"] in bartype else b["Btype"],
               "bars": stype.get(b["Stype"], {}).get("required", 1), "makes": iname(b["product"]), "level": b["level"],
               "xp": round(bartype.get(b["Btype"], {}).get("experience", 0) * stype.get(b["Stype"], {}).get("required", 1), 1)}
              for b in E("global.skill.smithing.Bars")],
}

S["runecrafting"] = {
    "runes": [{"rune": iname(r["rune"]), "level": r["level"], "xp": r["experience"], "altar": nice(r["_name"]) + " altar"} for r in E("global.skill.runecrafting.Rune")],
    "ruins": [], "combination": [{"rune": iname(c["rune"]), "level": c["level"], "xp": c["experience"]} for c in E("global.skill.runecrafting.CombinationRune")],
    "pouches": [{"pouch": iname(p["pouch"]), "level": p["level"], "holds": p["capacity"]} for p in E("global.skill.runecrafting.RunePouch")],
}
tal = {t["_name"]: iname(t["talisman"]) for t in E("global.skill.runecrafting.Talisman")}
tia = {t["_name"]: iname(t["tiara"]) for t in E("global.skill.runecrafting.Tiara")}
for r in E("global.skill.runecrafting.MysteriousRuin"):
    b = r["base"]
    S["runecrafting"]["ruins"].append({"altar": nice(r["_name"]) + " altar", "talisman": tal.get(r["talisman"]), "tiara": tia.get(r["tiara"]),
                                       "x": b["x"], "y": b["y"], "z": b["z"], "near": nearest_place(b["x"], b["y"])})
    PLACES.append({"name": nice(r["_name"]) + " altar", "x": b["x"], "y": b["y"], "z": b["z"], "kind": "runecrafting altar"})

S["thieving"] = {
    "pickpocket": [{"npc": NPC.get(p["ids"][0], {}).get("name", nice(p["_name"])) if p["ids"] else nice(p["_name"]), "level": p["requiredLevel"], "xp": p["experience"]} for p in E("global.skill.thieving.Pickpockets")],
    "stalls": [{"stall": obj_name(s["full_ids"][0]) if s["full_ids"] else nice(s["_name"]), "level": s["level"], "xp": s["experience"], "gives": [x["name"] for x in items_of(s["rewards"])][:8]} for s in E("global.skill.thieving.Stall")],
    "chests": [{"chest": obj_name(c["objectIds"][0]) if c["objectIds"] else nice(c["_name"]), "name": nice(c["_name"]), "level": c["level"], "xp": c["experience"]} for c in E("global.skill.thieving.ThievableChestPlugin$Chest")],
}

S["farming"] = {
    "seeds": [{"seed": iname(p["itemID"]), "patch": nice(p["applicablePatch"]), "level": p["requiredLevel"], "plant_xp": p["plantingXP"], "harvest_xp": p["harvestXP"],
               "check_xp": p["checkHealthXP"], "gives": iname(p["harvestItem"]), "protect": [x["name"] for x in items_of([p.get("protectionItem"), p.get("protectionItemSecondary")]) if x["name"]]}
              for p in E("global.skill.farming.Plantable")],
    "patch types": [nice(p["_name"]) for p in E("global.skill.farming.PatchType")],
    "patches": [{"patch": nice(p["_name"]), "type": nice(p["type"])} for p in E("global.skill.farming.FarmingPatch")],
    "farmers": [{"farmer": NPC.get(f["id"], {}).get("name", nice(f["_name"])), "patches": [nice(x) for x in f["patches"]]} for f in E("global.skill.farming.Farmers")],
    "compost": [nice(c["_name"]) for c in E("global.skill.farming.CompostType")],
    "tools": ["Rake", "Seed dibber", "Spade", "Secateurs", "Watering can(8)", "Gardening trowel", "Plant cure"],
}
patch_pts = obj_points([int(k) for k in objmap if not OBJ.get(int(k), {}).get("name") and "patch" in obj_name(int(k)).lower()])
for (x, y, z, n) in cluster(patch_pts, 8):
    area = nearest_place(x, y, z)
    if area:
        nm = f"{area} farming patches"
        if nm not in taken:
            taken.add(nm)
            PLACES.append({"name": nm, "x": x, "y": y, "z": z, "kind": "farming"})

traps = []
for t in E("global.skill.hunter.Traps"):
    st = t["settings"] or {}
    for node in t["nodes"] or []:
        if not isinstance(node, dict):
            continue
        for nid in node.get("npcIds") or []:
            if nid is None:
                continue
            traps.append({"trap": nice(t["_name"]), "item": iname(st.get("items")[0]) if st.get("items") else None, "creature": NPC.get(nid, {}).get("name"),
                          "level": node["level"], "xp": node["experience"], "bait": [iname(b) for b in st.get("baitIds") or []],
                          "gives": [x["name"] for x in items_of(node.get("rewards"))]})
S["hunter"] = {
    "traps": traps,
    "nets": [{"catch": NPC.get(b["node"]["npcs"][0], {}).get("name") if b["node"].get("npcs") else nice(b["_name"]), "level": b["node"]["levels"][0] if b["node"].get("levels") else None,
              "jar": iname(b["node"].get("reward"))} for b in E("global.skill.hunter.bnet.BNetTypes")],
    "falconry": [{"catch": NPC.get(f["npc"], {}).get("name"), "level": f["level"], "xp": f["experience"], "gives": iname(f["item"])} for f in E("global.skill.hunter.falconry.FalconCatch")],
    "tracking": [nice(t["_name"]) for t in E("global.skill.hunter.tracking.TrailType")],
    "gear": [nice(g["_name"]) for g in E("global.skill.hunter.HunterGear")],
}

S["summoning"] = {
    "pouches": [{"pouch": iname(p["pouchId"]), "level": p["levelRequired"], "xp": p["createExperience"], "needs": items_of(p.get("items")),
                 "familiar": NPC.get(p["npcId"], {}).get("name"), "cost": p["summonCost"]} for p in E("global.skill.summoning.SummoningPouch")],
    "scrolls": [{"scroll": iname(s["itemId"]), "level": s["level"], "xp": s["xp"], "needs": items_of(s.get("items"))} for s in E("global.skill.summoning.SummoningScroll")],
}

S["slayer"] = {
    "masters": [{"master": NPC.get(m["npc_id"], {}).get("name", nice(m["_name"])), "combat": m["required_combat"], "slayer": m["required_slayer"],
                 "tasks": [t["task"] for t in m["tasks"] if isinstance(t, dict)]} for m in E("global.skill.slayer.Master")],
    "tasks": [{"task": nice(t["_name"]), "level": t["levelReq"], "monsters": sorted({NPC.get(i, {}).get("name") for i in t["ids"] if NPC.get(i)})} for t in E("global.skill.slayer.Tasks")],
}

dec = E("global.skill.construction.Decoration")
S["construction"] = {
    "rooms": [{"room": nice(r["_name"]), "level": r["level"], "cost": r["cost"]} for r in E("global.skill.construction.RoomProperties")],
    "furniture": [{"build": iname(d["interfaceItem"]) or nice(d["_name"]), "level": d["level"], "xp": d["experience"], "needs": items_of(d["items"])} for d in dec],
    "houses": [{"town": nice(h["_name"]), "level": h["levelRequirement"]} for h in E("global.skill.construction.HouseLocation")],
}

# magic: spellbook entries plus the named spells and their levels/runes from the listeners' source
spell_names = {}
for book in ("Modern", "Ancient", "Lunar"):
    for k, v in (enums.get("content.global.skill.magic.spellconsts." + book) or {}).items():
        spell_names[(book.upper(), v)] = nice(k)
COMBAT = {"AirSpell": "Wind", "WaterSpell": "Water", "EarthSpell": "Earth", "FireSpell": "Fire", "IceSpells": "Ice", "SmokeSpells": "Smoke",
          "ShadowSpells": "Shadow", "BloodSpells": "Blood", "MiasmicSpells": "Miasmic"}
# the game's own names for spells whose data carries no name (ancient teleports are known by level)
ANCIENT_TELEPORTS = {0: "Home teleport (Ancient)", 54: "Paddewwa teleport", 60: "Senntisten teleport", 66: "Kharyrll teleport", 72: "Lassar teleport",
                     78: "Dareeyak teleport", 84: "Carrallangar teleport", 90: "Annakarl teleport", 96: "Ghorrock teleport"}
GOD_SPELLS = {41: "Saradomin strike", 42: "Claws of Guthix", 43: "Flames of Zamorak"}
ENCHANT_LEVELS = [7, 27, 49, 57, 68, 87]
spells = []
for book in enums.get("core.game.node.entity.player.link.SpellBookManager$SpellBook", []):
    for sid, sp in (book.get("spells") or {}).items():
        if not isinstance(sp, dict):
            continue
        cls = sp.get("_class", "")
        if cls == "SonicWaveSpell":
            continue                         # a placeholder entry, not a real spell
        name = spell_names.get((book["_name"], int(sid)))
        if not name and cls == "AncientTeleportPlugin":
            name = ANCIENT_TELEPORTS.get(sp.get("level"))
        if not name and cls == "TeleotherSpells":
            name = "Tele other " + str(sp.get("destination", "")).title()
        if not name and cls == "GodSpells":
            name = GOD_SPELLS.get(int(sid))
        if not name and cls == "EnchantSpell":
            name = "Enchant level " + str(ENCHANT_LEVELS.index(sp.get("level")) + 1 if sp.get("level") in ENCHANT_LEVELS else "")
        if not name and sp.get("type") and cls not in COMBAT:
            name = nice(sp["type"])
        if not name and cls in COMBAT:
            name = COMBAT[cls] + " " + (sp.get("type") or "").lower()
        if not name:
            name = re.sub(r"(Spell|Spells)$", "", cls)
            name = re.sub(r"(?<!^)([A-Z])", r" \1", name) + (" " + sp["type"].lower() if sp.get("type") else "")
        spells.append({"spell": name.strip()[:1].upper() + name.strip()[1:], "book": book["_name"].title(), "level": sp.get("level"), "xp": sp.get("experience"),
                       "runes": items_of(sp.get("runes")), "combat": cls in COMBAT or "type" in sp, "button": int(sid)})
listener_src = ""
for f in ("global/skill/magic/modern/ModernListeners.kt", "global/skill/magic/lunar/LunarListeners.kt"):
    try:
        listener_src += open(os.path.join(SRC, f), encoding="utf-8").read()
    except OSError:
        pass
for m in re.finditer(r"onCast\((Modern|Lunar|Ancient)\.(\w+)", listener_src):
    book, key = m.group(1), m.group(2)
    if any(s["spell"] == nice(key) for s in spells):
        continue
    body = listener_src[m.end(): m.end() + 900]
    lvl = re.search(r"requires\(player,\s*(\d+)", body)
    runes = re.findall(r"Item\(Items\.(\w+?)_(\d+),\s*(\d+)\)", body.split("onCast(")[0])
    spells.append({"spell": nice(key), "book": book, "level": int(lvl.group(1)) if lvl else None, "xp": None,
                   "runes": [{"name": nice(r[0]), "amount": int(r[2])} for r in runes], "combat": False,
                   "button": (enums.get("content.global.skill.magic.spellconsts." + book) or {}).get(key)})
S["magic"] = {"spells": spells, "orbs": [{"orb": iname(o["chargedOrb"]), "obelisk": obj_name(o["obelisk"]), "level": o["level"], "xp": o["experience"]} for o in E("global.skill.magic.modern.ChargeOrbData")]}

S["agility"] = {"courses": [], "shortcuts": []}
agl = os.path.join(SRC, "global/skill/agility")
for root, _, files in os.walk(agl):
    for f in files:
        txt = open(os.path.join(root, f), encoding="utf-8").read()
        if "Course" in f and "Shortcut" not in f:
            lv = re.findall(r"getStaticLevel\(Skills\.AGILITY\)\s*<\s*(\d+)|AGILITY,\s*(\d+)", txt)
            S["agility"]["courses"].append({"course": re.sub(r"(?<!^)([A-Z])", r" \1", f.split(".")[0]).replace(" Course", " course"),
                                            "level": min([int(a or b) for a, b in lv] or [1])})
        elif "Shortcut" in f and f not in ("AgilityShortcut.java",):
            S["agility"]["shortcuts"].append(re.sub(r"(?<!^)([A-Z])", r" \1", f.split(".")[0]).replace(" Shortcut", ""))
S["agility"]["courses"] = [c for c in S["agility"]["courses"] if c["course"] not in ("Agility course",)]
# levels and lap order from the course code (GnomeStrongholdCourse, BarbarianOutpostCourse, WildernessCourse...)
COURSES = {
    "Gnome Stronghold course": dict(level=1, x=2474, y=3438, z=0, laps=[
        {"ids": [2295], "option": "Walk-across"}, {"ids": [2285], "option": "Climb-over"}, {"ids": [35970], "option": "Climb"},
        {"ids": [2312], "option": "Walk-on"}, {"ids": [2314, 2315], "option": "Climb-down"}, {"ids": [2286], "option": "Climb-over"},
        {"ids": [4058, 154], "option": "Squeeze-through"}]),
    "Barbarian Outpost course": dict(level=35, x=2552, y=3562, z=0, enter={"ids": [20210], "option": "Squeeze-through"}, note="Enter through the pipe (Agility 35) or the gate (Alfred Grimhand's Barcrawl).", laps=[
        {"ids": [2282], "option": "Swing-on"}, {"ids": [2294], "option": "Walk-across"}, {"ids": [20211], "option": "Climb-over"},
        {"ids": [2302], "option": "Walk-across"}, {"name": "Ladder", "option": "Climb-down"},
        {"ids": [1948], "option": "Climb-over"}, {"ids": [1948], "option": "Climb-over"}, {"ids": [1948], "option": "Climb-over"}]),
    "Wilderness course": dict(level=52, x=2998, y=3916, z=0, enter={"ids": [2309], "option": "Open"}, note="In the Wilderness: other monsters can attack you here.", laps=[
        {"ids": [2288], "option": "Squeeze-through"}, {"ids": [2283], "option": "Swing-on"}, {"ids": [37704], "option": "Cross"},
        {"ids": [2297], "option": "Walk-across"}, {"ids": [2328], "option": "Climb"}]),
    # the pyramid: every obstacle by where it is (floors 3 and 4 are on the map elsewhere), `stand` where a ledge or gap
    # must be started from, `top` for the rocks with the pyramid top (no XP once it's taken this lap)
    "Agility Pyramid course": dict(level=30, x=3354, y=2830, z=0, note="Five floors with moving blocks; climbs to the top for the pyramid top each lap. Bring waterskins for the desert heat.",
        laps=[{"name": "Stairs", "option": "Climb-up", "at": [3354, 2831, 0], "stand": [3354, 2830]}, {"name": "Low wall", "option": "Climb-over", "at": [3354, 2849, 1], "stand": [3354, 2848]}, {"name": "Ledge", "option": "Cross", "at": [3364, 2851, 1], "stand": [3363, 2851]}, {"name": "Plank", "option": "Cross", "at": [3375, 2845, 1], "stand": [3375, 2845]}, {"name": "Gap", "option": "Cross", "at": [3372, 2832, 1], "stand": [3372, 2832]}, {"name": "Ledge", "option": "Cross", "at": [3362, 2831, 1], "stand": [3364, 2831]}, {"name": "Stairs", "option": "Climb-up", "at": [3356, 2833, 1], "stand": [3356, 2832]}, {"name": "Gap", "option": "Cross", "at": [3357, 2836, 2], "stand": [3356, 2835]}, {"name": "Gap", "option": "Jump", "at": [3356, 2847, 2], "stand": [3356, 2846]}, {"name": "Gap", "option": "Cross", "at": [3359, 2849, 2], "stand": [3359, 2849]}, {"name": "Ledge", "option": "Cross", "at": [3372, 2839, 2], "stand": [3372, 2841]}, {"name": "Low wall", "option": "Climb-over", "at": [3370, 2833, 2], "stand": [3371, 2833]}, {"name": "Gap", "option": "Jump", "at": [3364, 2833, 2], "stand": [3366, 2833]}, {"name": "Stairs", "option": "Climb-up", "at": [3358, 2835, 2], "stand": [3358, 2834]}, {"name": "Low wall", "option": "Climb-over", "at": [3358, 2839, 3], "stand": [3358, 2838]}, {"name": "Ledge", "option": "Cross", "at": [3358, 2843, 3], "stand": [3359, 2842]}, {"name": "Gap", "option": "Jump", "at": [3370, 2841, 3], "stand": [3370, 2843]}, {"name": "Plank", "option": "Cross", "at": [3370, 2835, 3], "stand": [3370, 2835]}, {"name": "Stairs", "option": "Climb-up", "at": [3360, 2837, 3], "stand": [3360, 2836]}, {"name": "Gap", "option": "Jump", "at": [3040, 4697, 2], "stand": [3040, 4695]}, {"name": "Low wall", "option": "Climb-over", "at": [3042, 4701, 2], "stand": [3040, 4699]}, {"name": "Gap", "option": "Jump", "at": [3048, 4695, 2], "stand": [3048, 4697]}, {"name": "Low wall", "option": "Climb-over", "at": [3047, 4693, 2], "stand": [3048, 4694]}, {"name": "Stairs", "option": "Climb-up", "at": [3042, 4695, 2], "stand": [3042, 4694]}, {"name": "Climbing rocks", "option": "Climb", "at": [3043, 4697, 3], "top": True, "stand": [3042, 4697]}, {"name": "Gap", "option": "Jump", "at": [3046, 4697, 3], "stand": [3044, 4699]}, {"name": "Doorway", "option": "Enter", "at": [3044, 4696, 3], "stand": [3046, 4696]}]),
    "Brimhaven course": dict(level=1, x=2809, y=3193, z=0, note="The arena pays in tickets: use Click Object for its obstacles.", laps=None),
}
for c in S["agility"]["courses"]:
    c.update(COURSES.get(c["course"], {}))

for k in ("attack", "strength", "defence", "hitpoints", "ranged"):
    S[k] = {"see": "monsters"}
S["combat"] = {"food": sorted({iname(i) for c in E("data.consumables.Consumables") for i in (c.get("consumable") or {}).get("ids", []) if iname(i)})}

# ---------------------------------------------------------------- one list of "use X on Y" recipes for the Use engine's checks
RECIPES = []
def rec(skill, use, on, makes, level, xp=None, **extra):
    if use and on and makes:
        RECIPES.append(dict(skill=skill, use=use, on=on, makes=makes, level=level or 1, xp=xp, **extra))
for h in S["herblore"]["unfinished"]: rec("herblore", h["use"], h["on"], h["makes"], h["level"])
for h in S["herblore"]["potions"]: rec("herblore", h["use"], h["on"], h["makes"], h["level"], h["xp"])
for g in S["herblore"]["grind"]: rec("herblore", "Pestle and mortar", g["item"], g["makes"], 1)
for t in S["herblore"]["tar"]: rec("herblore", t["herb"], "Swamp tar", t["makes"], t["level"], t["xp"])
for grp, rows in S["fletching"].items():
    for r in rows:
        if "log" in r: rec("fletching", "Knife", r["log"], r["makes"], r["level"], r["xp"])
        else: rec("fletching", r["use"], r["on"], r["makes"], r["level"], r["xp"])
for grp, rows in S["crafting"].items():
    for r in rows:
        if "use" in r: rec("crafting", r["use"], r["on"], r["makes"], r.get("level"), r.get("xp"), amount=r.get("amount", 1))
for r in S["cooking"]["combine"]: rec("cooking", r["use"], r["on"], r["makes"], r["level"], r["xp"])
for f in S["cooking"]["food"]: rec("cooking", f["raw"], "Range", f["cooked"], f["level"], f["xp"], station=True)
for b in S["smithing"]["smelt"]:
    if b["ores"]: rec("smithing", b["ores"][0]["name"], "Furnace", b["makes"], b["level"], b["xp"], station=True, needs=b["ores"])
for b in S["smithing"]["smith"]: rec("smithing", b["bar"], "Anvil", b["makes"], b["level"], b["xp"], station=True, bars=b["bars"], tool="Hammer")
for s in S["farming"]["seeds"]: rec("farming", s["seed"], s["patch"], s["gives"], s["level"], s["plant_xp"])
for p in S["summoning"]["pouches"]: rec("summoning", "Pouch", "Obelisk", p["pouch"], p["level"], p["xp"], needs=p["needs"])

# quests the server checks before a skill action (from the server's own "you need to complete..." messages)
QUEST_SKILLS = {"herblore": "Druidic Ritual"}
for r in RECIPES:
    m = r["makes"].lower()
    if r["skill"] in QUEST_SKILLS: r["quest"] = QUEST_SKILLS[r["skill"]]
    elif r["skill"] in ("fletching", "smithing") and "dart" in m: r["quest"] = "Tourist Trap"
    elif r["skill"] == "smithing" and "claws" in m: r["quest"] = "Death Plateau"

# ---------------------------------------------------------------- shops
shops = []
for s in shops_raw:
    stock = [iname(int(m)) for m in re.findall(r"\{(\d+),", s.get("stock", ""))]
    keepers = [NPC.get(int(n), {}).get("name") for n in s.get("npcs", "").split(",") if n.strip().isdigit()]
    shops.append({"shop": s["title"], "keepers": [k for k in keepers if k], "stock": [x for x in stock if x][:80]})

# ---------------------------------------------------------------- editor lists
# Ready-made pick lists for the block editor: [name, note, skill, level, needs]. The note shows next to the name.
PRAYERS = [("Thick skin", 1), ("Burst of strength", 4), ("Clarity of thought", 7), ("Sharp eye", 8), ("Mystic will", 9), ("Rock skin", 10),
           ("Superhuman strength", 13), ("Improved reflexes", 16), ("Rapid restore", 19), ("Rapid heal", 22), ("Protect items", 25),
           ("Hawk eye", 26), ("Mystic lore", 27), ("Steel skin", 28), ("Ultimate strength", 31), ("Incredible reflexes", 34),
           ("Protect from summoning", 35), ("Protect from magic", 37), ("Protect from missiles", 40), ("Protect from melee", 43),
           ("Eagle eye", 44), ("Mystic might", 45), ("Retribution", 46), ("Redemption", 49), ("Smite", 52), ("Chivalry", 60), ("Piety", 70)]
def row(name, note="", skill="", level=0, needs="", quest=""):
    r = [name, note, skill, level or 0, needs]
    if quest: r.append(quest)
    return r
def uniq(rows):
    seen, out = set(), []
    for r in rows:
        if r[0].lower() in seen: continue
        seen.add(r[0].lower()); out.append(r)
    return out
def lvl(skill, level): return f"{skill.title()} {level}" if level else ""
L = {}
# Walk To tests (walk_fix.json, from walking a test character to every place): spots moved to where the place can
# actually be walked to, and places that can't be reached on foot (boats, quests, closed areas) left out
NOWALK = os.path.join(HERE, "walk_fix_people.json")    # people and monsters Walk To can't reach from the mainland
if os.path.exists(NOWALK):
    for n in json.load(open(NOWALK)):
        for coll in (people, monsters):
            if n in coll:
                coll[n]["walk"] = False
FIX = os.path.join(HERE, "walk_fix.json")
if os.path.exists(FIX):
    fix = json.load(open(FIX))
    kept = []
    for p in PLACES:
        f = fix.get(p["name"], "keep")
        if f is None:
            continue
        if f != "keep":
            p["x"], p["y"], p["z"] = f
        kept.append(p)
    PLACES[:] = kept

L["place"] = [row(p["name"], p["kind"] if p["kind"] != "area" else "") for p in sorted(PLACES, key=lambda p: p["name"])] \
           + [row(c["course"], "agility course", "agility", c.get("level", 1)) for c in S["agility"]["courses"] if c.get("x")]
L["tree"] = uniq([row(t["tree"], lvl("woodcutting", t["level"]) + f" · {t['gives']}", "woodcutting", t["level"], "an axe")
                  for t in sorted(S["woodcutting"]["trees"], key=lambda t: t["level"]) if not t.get("farming")])
L["rock"] = uniq([row(r["ore"].replace(" ore", ""), lvl("mining", r["level"]), "mining", r["level"], "a pickaxe") for r in sorted(S["mining"]["rocks"], key=lambda r: r["level"])])
L["fish"] = uniq([row(f["name"].replace("Raw ", "").capitalize(), lvl("fishing", f["level"]) + f" · {m['method'].lower()}", "fishing", f["level"],
                      m["tool"] + (" and " + m["bait"][0] if m["bait"] else "")) for m in S["fishing"]["methods"] for f in m["fish"]])
L["fish"].sort(key=lambda r: r[3])
L["monster"] = sorted([row(n, f"{v.get('hp', 0)} HP" if v.get("hp") else "") for n, v in monsters.items() if "Attack" in (v.get("options") or [])], key=lambda r: r[0])
# anyone you can do something with besides fight: Talk-to (any spelling), Trade, Pickpocket, Bank, Travel...
def clickable(v): return any(o and o.lower() not in ("attack", "examine", "hidden", "null") for o in (v.get("options") or []))
L["npc"] = sorted(uniq([row(n, ", ".join((v.get("areas") or [])[:2])) for n, v in list(people.items()) + list(monsters.items()) if n and clickable(v)]), key=lambda r: r[0])
L["item"] = sorted([row(n) for n in ITEMS if n[:1].isalnum()], key=lambda r: r[0])
L["food"] = [row("Any food")] + [row(f) for f in sorted(S["combat"]["food"])]
L["logs"] = [row("Any logs")] + uniq([row(l["log"], lvl("firemaking", l["level"]), "firemaking", l["level"], "a tinderbox") for l in sorted(S["firemaking"]["logs"], key=lambda l: l["level"])])
L["bones"] = [row("Any bones")] + uniq([row(b["bones"], f"{b['xp']} XP") for b in S["prayer"]["bones"]])
prod = []
for r in RECIPES:
    if r["skill"] == "farming": continue
    prod.append(row(r["makes"], lvl(r["skill"], r.get("level")) + f" · {r['use']} on {r['on']}", r["skill"], r.get("level"),
                    f"{r['use']} and {r['on']}" if r["on"] not in ("Range", "Furnace", "Anvil", "Obelisk") else f"{r['use']} at {'an' if r['on'][0] in 'AEIOU' else 'a'} {r['on'].lower()}", r.get("quest", "")))
for r in S["crafting"]["tanning"]: prod.append(row(r["makes"], f"tanning · {r['hide']}", "crafting", 0, f"{r['hide']} and coins, at a tanner"))
L["product"] = sorted(uniq(prod), key=lambda r: r[0])
# things that go into a "use X on Y" recipe, with how many recipes use them
ING = {}
for r in RECIPES:
    if r["skill"] == "farming": continue
    for n in (r["use"], r["on"]):
        if n not in ("Range", "Furnace", "Anvil", "Obelisk"): ING[n] = ING.get(n, 0) + 1
L["ingredient"] = sorted([row(n, f"used in {c} recipe{'s' if c > 1 else ''}") for n, c in ING.items()], key=lambda r: r[0])
L["spell"] = uniq([row(s["spell"], f"{s['book']} · Magic {s['level']}" if s.get("level") else s["book"], "magic", s.get("level"),
                       ", ".join(f"{x['amount']} {x['name']}" for x in s.get("runes") or [])) for s in S["magic"]["spells"] if s.get("button") is not None])
L["prayer"] = [row(n, f"Prayer {l}", "prayer", l, "King's Ransom quest" if l >= 60 else "") for n, l in PRAYERS]
L["course"] = [row(c["course"], f"Agility {c.get('level', 1)}" + ("" if c.get("laps") else " · use Click Object"), "agility", c.get("level", 1), c.get("note", "")) for c in S["agility"]["courses"]]
L["thieving"] = [row(r["npc"], f"pickpocket · Thieving {r['level']}", "thieving", r["level"]) for r in S["thieving"]["pickpocket"]] \
              + [row(r["stall"], f"stall · Thieving {r['level']}", "thieving", r["level"]) for r in S["thieving"]["stalls"]] \
              + [row(r["name"] + " chest", f"chest · Thieving {r['level']}", "thieving", r["level"]) for r in S["thieving"]["chests"]]
L["thieving"] = uniq(L["thieving"])
L["creature"] = uniq([row(t["creature"], f"{t['trap'].lower()} · Hunter {t['level']}", "hunter", t["level"], t.get("item") or t["trap"]) for t in sorted(S["hunter"]["traps"], key=lambda t: t["level"])])
L["trap"] = uniq([row(t["trap"], "", "hunter", 0) for t in S["hunter"]["traps"]])
L["seed"] = uniq([row(s["seed"], f"{s['patch'].lower()} · Farming {s['level']}", "farming", s["level"], "a seed dibber" if "sapling" not in s["seed"].lower() else "a spade") for s in sorted(S["farming"]["seeds"], key=lambda s: s["level"])])
L["patch"] = [row("Farming patch", "the nearest one")] + uniq([row(t, "") for t in sorted({p["type"] for p in S["farming"]["patches"]})])
L["familiar"] = uniq([row(p["familiar"], f"Summoning {p['level']} · {p['cost']} point{'s' if p['cost'] != 1 else ''}", "summoning", p["level"], p["pouch"]) for p in S["summoning"]["pouches"] if p.get("familiar")])
L["furniture"] = uniq([row(f["build"], f"Construction {f['level']}", "construction", f["level"], ", ".join(f"{x['amount']} {x['name']}" for x in f["needs"])) for f in sorted(S["construction"]["furniture"], key=lambda f: f["level"]) if f["level"] >= 0])
L["quest"] = sorted([row(q["questName"]) for q in E("content.data.Quests") if q.get("questName")], key=lambda r: r[0])
L["skill"] = [row(s.title()) for s in SKILLS]
L["option"] = [row(o) for o in ["Open", "Close", "Climb-up", "Climb-down", "Enter", "Search", "Pick", "Pick-from", "Take", "Talk-to", "Trade",
               "Pickpocket", "Steal-from", "Use", "Pray-at", "Craft-rune", "Cross", "Jump", "Squeeze-through", "Climb-over", "Bank", "Collect"]]
L["key"] = [row("Space", "continue"), row("1", "first option"), row("2", "second option"), row("3", "third option"), row("4", "fourth option"),
            row("5", "fifth option"), row("Escape", "close"), row("Space, Space", "continue twice"), row("Space, 1", "continue, then option 1")]
L["chat"] = [row("Dialogue"), row("Options"), row("Make menu"), row("Input box"), row("No dialogue")]
L["onoff"] = [row("On"), row("Off")]
L["recharge"] = [row("Prayer", "pray at an altar"), row("Summoning", "renew points at an obelisk")]
L["housemode"] = [row("Building mode"), row("Normal")]
L["object"] = sorted([row(n, ", ".join(v.get("options") or [])) for n, v in objects.items() if v.get("options")], key=lambda r: r[0])
# the options each object and person has, for the Option slot of Click Object / Click Person
L["options"] = {}
for n, v in list(objects.items()) + list(people.items()) + list(monsters.items()):
    for o in v.get("options") or []:
        if o not in L["options"].setdefault(n, []): L["options"][n].append(o)

catalog = {
    "format": "code-edition-catalog", "version": 1,
    "built": datetime.date.today().isoformat(),
    "source": "2009scape single-player, July 2026 server and cache",
    "skills_list": SKILLS,
    "places": sorted(PLACES, key=lambda p: (p["kind"] != "area", p["name"])),
    "items": ITEMS,
    "monsters": monsters,
    "npcs": people,
    "objects": objects,
    "skills": S,
    "recipes": RECIPES,
    "skill_quests": QUEST_SKILLS,
    "shops": shops,
    "editor": L,
}
out = os.path.join(HERE, "catalog.json")
with open(out, "w", encoding="utf-8") as f:
    json.dump(catalog, f, ensure_ascii=False, separators=(",", ":"))

# ---------------------------------------------------------------- coverage report
lines = [f"# Catalogue coverage ({catalog['built']})", "",
         f"- Places: {len(PLACES)} ({sum(1 for p in PLACES if p['kind']=='area')} map areas, {sum(1 for p in PLACES if p['kind']!='area')} banks, furnaces, altars, patches and other stations)",
         f"- Items: {len(ITEMS)}", f"- Monsters: {len(monsters)}", f"- Other NPCs: {len(people)}", f"- Objects: {len(objects)}",
         f"- Use-on recipes: {len(RECIPES)}", f"- Shops: {len(shops)}", "", "| Skill | Tables | Rows |", "| --- | --- | --- |"]
for k in SKILLS + ["combat"]:
    v = S.get(k)
    if not v:
        lines.append(f"| {k} | none | 0 |")
        continue
    t = [(n, len(r)) for n, r in v.items() if isinstance(r, list)]
    lines.append(f"| {k} | {', '.join(n for n, _ in t) or 'monsters'} | {sum(c for _, c in t) or len(monsters)} |")
open(os.path.join(HERE, "coverage.md"), "w").write("\n".join(lines) + "\n")
print("\n".join(lines))
print("catalog.json", os.path.getsize(out), "bytes")
