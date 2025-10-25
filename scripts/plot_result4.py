import os, glob, json
import matplotlib.pyplot as plt
import matplotlib.lines as mlines

# -------------------- CONFIG --------------------
DATA_DIR = r"C:\gitwork\patriot_logger_android\app"   # where result_*.json live

FIG_WIDTH_PX  = 3400
FIG_HEIGHT_PX = 2000
DPI = 100
FIGSIZE = (FIG_WIDTH_PX / DPI, FIG_HEIGHT_PX / DPI)

N_COLS = 5
N_ROWS = 4

YMIN = -105
YMAX = -60

RUN_MAX_ROWS   = 70
WALK_MIN_ROWS  = 140
CLOSE_PEAK_DBM = -78

STATE_KEYS = ("APPROACHINGS","HERES","LOGGEDS","PEAKS")
STATE_LABEL = {"APPROACHINGS":"APPR","HERES":"HERE","LOGGEDS":"LOG","PEAKS":"PEAK"}
STATE_MARKER = {"APPROACHINGS":"o","HERES":"x","LOGGEDS":"P","PEAKS":"*"}
STATE_SIZE = {"APPROACHINGS":60,"HERES":70,"LOGGEDS":90,"PEAKS":220}
STATE_TEXT_OFFSET = {"APPROACHINGS":(0,-12),"HERES":(0,-12),"LOGGEDS":(0,-12),"PEAKS":(0,-14)}
STATE_FONTSIZE = {"APPROACHINGS":8,"HERES":8,"LOGGEDS":8,"PEAKS":9}

TRACK_PALETTE = [
    "tab:orange","tab:green","tab:red","tab:purple","tab:brown",
    "tab:pink","tab:gray","tab:olive","tab:cyan","#a65628"
]

def guess_speed(n_rows):
    if n_rows <= RUN_MAX_ROWS: return "Run"
    if n_rows >= WALK_MIN_ROWS: return "Walk"
    return "Jog"

def guess_distance(peak_rssi):
    if peak_rssi is None: return "?"
    return "Close" if peak_rssi >= CLOSE_PEAK_DBM else "Far"

def choose_algorithm(algos: dict):
    if not algos: return None
    return list(algos.keys())[0]

def load_results(paths):
    items = []
    for p in paths:
        try:
            d = json.load(open(p, "r", encoding="utf-8"))
        except Exception:
            continue
        series = d.get("series", [])
        n = len(series)
        peak = None
        if series:
            vals = [v.get("rssi") for v in series if isinstance(v.get("rssi"), (int,float))]
            peak = max(vals) if vals else None
        algos = d.get("algorithms", {}) or {}
        chosen_name = choose_algorithm(algos)
        items.append({
            "path": p,
            "name": d.get("sampleFile", os.path.basename(p)),
            "series": series,                    # rssi + recomputed smoothed
            "algos":  algos,
            "chosen_alg_name": chosen_name,
            "chosen_alg_data": algos.get(chosen_name, {}) if chosen_name else {},
            "nrows":  n,
            "peak":   peak,
            "speed":  guess_speed(n),
            "dist":   guess_distance(peak),
        })
    return items

def sort_groups(items):
    groups = {"Run": [], "Jog": [], "Walk": []}
    for it in items:
        sp = it["speed"]
        if str(sp).lower().startswith("run"): sp = "Run"
        groups.setdefault(sp, []).append(it)
    for k in groups:
        groups[k] = sorted(groups[k], key=lambda x: x["name"])
    return groups["Run"] + groups["Jog"] + groups["Walk"], groups

def track_color(track_id):
    idx = (int(track_id) - 1) % len(TRACK_PALETTE)
    return TRACK_PALETTE[idx]

def nearest_series_y(series, t_ms):
    if not series or t_ms is None:
        return None
    best = None
    best_dt = None
    for p in series:
        tm = p.get("t"); vv = p.get("rssi")
        if tm is None or vv is None:
            continue
        dt = abs(tm - t_ms)
        if best_dt is None or dt < best_dt:
            best_dt = dt; best = vv
    return best

# -------------------- PLOTTING --------------------
def plot_all(items):
    fig = plt.figure(figsize=FIGSIZE, dpi=DPI, constrained_layout=False)
    gs = fig.add_gridspec(
        nrows=N_ROWS, ncols=N_COLS,
        left=0.012, right=0.995, top=0.96, bottom=0.055,
        wspace=0.12, hspace=0.26
    )
    fig.suptitle("All samples — Run (top), Jog (middle), Walk (bottom) • X: seconds, Y: RSSI (dBm)", fontsize=16)

    axes = [[fig.add_subplot(gs[r, c]) for c in range(N_COLS)] for r in range(N_ROWS)]
    ordered_items, _ = sort_groups(items)
    total = len(ordered_items)
    max_slots = N_ROWS * N_COLS

    for idx in range(max_slots):
        r = idx // N_COLS
        c = idx % N_COLS
        ax = axes[r][c]
        if idx >= total:
            ax.axis("off")
            continue

        item = ordered_items[idx]
        series = item["series"]
        t_ms = [p["t"] for p in series]
        raw  = [p["rssi"] for p in series]
        ema  = [p.get("smoothed") for p in series]
        t_s  = [tm / 1000.0 for tm in t_ms]

        # RAW measured points
        if t_s and raw:
            ax.plot(t_s, raw, lw=0.6, alpha=0.25, color="gray", zorder=1)
            ax.scatter(t_s, raw, s=22, facecolors='none', edgecolors='tab:blue',
                       linewidths=0.9, label="Measured", zorder=2)

        # Recomputed EMA line (same color family, dashed, thinner)
        if t_s and ema and any(v is not None for v in ema):
            ax.plot(t_s, ema, linewidth=1.2, linestyle="--",
                    color="tab:blue", alpha=0.9, label="EMA (from RAW)", zorder=3)

        # axis ranges
        ax.set_ylim((YMIN, YMAX))
        if t_s:
            tmin, tmax = min(t_s), max(t_s)
            pad = max(0.05, (tmax - tmin) * 0.05)
            ax.set_xlim((tmin, tmax + pad))

        # transitions
        alg_data = item["chosen_alg_data"] or {}
        tracks = (alg_data.get("tracks") or {}) if isinstance(alg_data.get("tracks"), dict) else {}
        for track_id_str, track_dict in sorted(tracks.items(), key=lambda kv:int(kv[0])):
            color = track_color(track_id_str)
            for state_key in STATE_KEYS:
                pts = (track_dict or {}).get(state_key, []) or []
                if not pts: continue
                xs, ys = [], []
                for node in pts:
                    tt = node.get("t"); yy = node.get("rssi")
                    if tt is None: continue
                    if yy is None or not isinstance(yy,(int,float)) or yy > -60 or yy < -120:
                        yy = nearest_series_y(series, tt)
                    if yy is None: continue
                    xs.append(tt/1000.0); ys.append(yy)
                if not xs: continue
                m = STATE_MARKER[state_key]
                s = STATE_SIZE[state_key]
                ax.scatter(xs, ys, s=s, color=color, marker=m, zorder=5,
                           linewidths=1.0, edgecolors='black' if state_key=="PEAKS" else None)
                lbl = STATE_LABEL[state_key]
                dx, dy = STATE_TEXT_OFFSET[state_key]
                fz = STATE_FONTSIZE[state_key]
                for (xi, yi) in zip(xs, ys):
                    ax.annotate(lbl, (xi, yi), textcoords="offset points", xytext=(dx, dy),
                                ha='center', fontsize=fz, color=color, zorder=6)

        # legend
        handles = [mlines.Line2D([], [], linestyle='None', marker='o',
                                 markerfacecolor='none', markeredgecolor='tab:blue',
                                 markersize=6, label='Measured')]
        if ema and any(v is not None for v in ema):
            handles.append(mlines.Line2D([], [], linestyle='--', linewidth=1.6,
                                         color='tab:blue', label='EMA (from RAW)'))
        ax.legend(handles=handles, loc="upper right", frameon=True, fontsize=8)

        ax.grid(True, ls="--", lw=0.5, alpha=0.45)
        ax.set_xlabel("time (s)")
        ax.set_ylabel("RSSI (dBm)")
        title_alg = item["chosen_alg_name"] if item["chosen_alg_name"] else "No alg"
        ax.set_title(f"{item['name']}  [{item['speed']} · {item['dist']}] (rows={item['nrows']})\n{title_alg}", fontsize=10)

    return fig

def main():
    paths = sorted(glob.glob(os.path.join(DATA_DIR, "result_*.json")))
    if not paths:
        print("No result_*.json found in:", DATA_DIR); return
    items = load_results(paths)
    if not items:
        print("No valid results loaded from:", DATA_DIR); return
    fig = plot_all(items)
    plt.show()

if __name__ == "__main__":
    main()
