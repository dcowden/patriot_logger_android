# plot_result3.py
import os, glob, json
import matplotlib.pyplot as plt
import matplotlib.lines as mlines

# -------------------- CONFIG --------------------
DATA_DIR = r"C:\gitwork\patriot_logger_android\app"   # where result_*.json live

# layout & sizing (3000x2000 px @ 100 dpi)
FIG_WIDTH_PX  = 3400
FIG_HEIGHT_PX = 2000
DPI = 100
FIGSIZE = (FIG_WIDTH_PX / DPI, FIG_HEIGHT_PX / DPI)

N_COLS = 5
N_ROWS = 4

# y-axis fixed for all charts (for easy comparison)
YMIN = -105
YMAX = -60

# classification helpers
RUN_MAX_ROWS   = 70
WALK_MIN_ROWS  = 140
CLOSE_PEAK_DBM = -78

def guess_speed(n_rows):
    if n_rows <= RUN_MAX_ROWS: return "Run"
    if n_rows >= WALK_MIN_ROWS: return "Walk"
    return "Jog"

def guess_distance(peak_rssi):
    if peak_rssi is None: return "?"
    return "Close" if peak_rssi >= CLOSE_PEAK_DBM else "Far"

# preferred colors/markers for known algs
ALG_STYLE = {
    "EMA(alpha=0.3)": dict(color="#1f77b4", m="^"),          # blue
    "MedianN(N=7)": dict(color="#ff7f0e", m="s"),            # orange
    "Kalman(Q=4.0,R=16.0)": dict(color="#2ca02c", m="D"),    # green
    "Median→EMA FSM (N=5, α=0.3, thrA=-90, thrH=-85, rise=3, fall=2, hyst=2dB)": dict(color="#9467bd", m="o"),
    "EMA+TimeExit(α=0.35, HERE@-80dBm, +2000ms)": dict(color="#8c564b", m="v"),
    "PowerDist(α=0.50, P1m=-70.0, n=2.00, thr=6.0m, N=2)": dict(color="#e377c2", m="P"),
    "TCA(α=0.30, P1m=-70.0, n=2.00, thr=2.50s, win=40)": dict(color="#7f7f7f", m="X"),
}

STATE_ORDER = ("APPROACHING", "HERE", "LOGGED", "PEAK")

# -------------------- UTILS --------------------
def nearest_series_y(series, t_ms):
    if not series: return None
    return min(series, key=lambda p: abs((p.get("t") or 0) - (t_ms or 0))).get("rssi")

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
        items.append({
            "path": p,
            "name": d.get("sampleFile", os.path.basename(p)),
            "series": series,
            "algos":  d.get("algorithms", {}),
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
        if sp.lower().startswith("run"):
            sp = "Run"
        groups.setdefault(sp, []).append(it)
    for k in groups:
        groups[k] = sorted(groups[k], key=lambda x: x["name"])
    ordered = groups["Run"] + groups["Jog"] + groups["Walk"]
    return ordered, groups

# -------------------- INTERACTIVE LEGEND --------------------
def attach_interactive_legend(fig, legend, label_to_artists, initially_hidden_labels):
    # Maps for legend icons/text by label
    handle_by_label = {}
    text_by_label = {}
    for handle, text in zip(legend.legend_handles, legend.get_texts()):
        lbl = text.get_text()
        handle_by_label[lbl] = handle
        text_by_label[lbl] = text

    # initialize visibilities
    for lbl, artists in label_to_artists.items():
        start_visible = lbl not in initially_hidden_labels
        for art in artists:
            art.set_visible(start_visible)
        h = handle_by_label.get(lbl)
        t = text_by_label.get(lbl)
        if h is not None: h.set_alpha(1.0 if start_visible else 0.25)
        if t is not None: t.set_alpha(1.0 if start_visible else 0.25)

    def on_pick(event):
        artist = event.artist
        lbl = None
        for k, h in handle_by_label.items():
            if artist is h:
                lbl = k; break
        if lbl is None:
            for k, tx in text_by_label.items():
                if artist is tx:
                    lbl = k; break
        if lbl is None: return

        arts = label_to_artists.get(lbl, [])
        if not arts: return

        make_visible = not any(a.get_visible() for a in arts)
        for a in arts:
            a.set_visible(make_visible)

        h = handle_by_label.get(lbl)
        t = text_by_label.get(lbl)
        if h is not None: h.set_alpha(1.0 if make_visible else 0.25)
        if t is not None: t.set_alpha(1.0 if make_visible else 0.25)

        fig.canvas.draw_idle()

    # Make legend pickable
    for h in legend.legend_handles:
        try: h.set_picker(7)
        except Exception: h.set_picker(True)
    for tx in legend.get_texts():
        tx.set_picker(True)

    fig.canvas.mpl_connect('pick_event', on_pick)

# -------------------- PLOTTING --------------------
def _first_color_from_artists(artists, fallback="#8B4513"):
    # Try to infer color from first line/scatter in artists list
    for a in artists:
        try:
            c = None
            if hasattr(a, "get_color"): c = a.get_color()
            elif hasattr(a, "get_edgecolor"): c = a.get_edgecolor()
            if isinstance(c, (list, tuple)) and len(c) > 0:
                return c[0]
            if c: return c
        except Exception:
            continue
    return fallback

def plot_all(items):
    fig = plt.figure(figsize=FIGSIZE, dpi=DPI, constrained_layout=False)
    gs = fig.add_gridspec(
        nrows=N_ROWS, ncols=N_COLS,
        left=0.012, right=0.995, top=0.958, bottom=0.055,
        wspace=0.12, hspace=0.2  # +~0.25 inch vertical space between rows

    )

    fig.suptitle("All samples — Run (top), Jog (middle), Walk (bottom) • X: seconds, Y: RSSI (dBm)", fontsize=16)

    axes = [[fig.add_subplot(gs[r, c]) for c in range(N_COLS)] for r in range(N_ROWS)]
    ordered_items, _ = sort_groups(items)
    total = len(ordered_items)
    max_slots = N_ROWS * N_COLS

    # label -> list of Artists across ALL subplots
    label_to_artists = {}

    for idx in range(max_slots):
        r = idx // N_COLS
        c = idx % N_COLS
        ax = axes[r][c]
        if idx >= total:
            ax.axis("off")
            continue

        item = ordered_items[idx]
        series = item["series"]

        # raw measured points
        t_ms = [p["t"] for p in series]
        rssi = [p["rssi"] for p in series]
        t_s = [tm / 1000.0 for tm in t_ms]

        if t_s and rssi:
            ln, = ax.plot(t_s, rssi, lw=0.6, alpha=0.28, color="gray")
            sc = ax.scatter(t_s, rssi, s=22, facecolors='none', edgecolors='tab:blue', linewidths=0.9)
            label_to_artists.setdefault("Measured", []).extend([ln, sc])

        # y/x ranges
        ax.set_ylim((YMIN, YMAX))
        if t_s:
            tmin, tmax = min(t_s), max(t_s)
            pad = max(0.05, (tmax - tmin) * 0.05)
            ax.set_xlim((tmin, tmax + pad))

        # algorithms
        for alg_name, states in item["algos"].items():
            sty = ALG_STYLE.get(alg_name, dict(color="#8B4513", m="x"))
            color = sty["color"]
            mshape = sty["m"]
            artists = []

            # smoothed line (dashed, no markers)
            line_data = states.get("line")
            if line_data:
                lt = [p.get("t")/1000.0 for p in line_data if p.get("t") is not None and p.get("v") is not None]
                lv = [p.get("v") for p in line_data if p.get("t") is not None and p.get("v") is not None]
                if lt and lv:
                    line, = ax.plot(lt, lv, color=color, linewidth=1.6, linestyle='--', alpha=0.95)
                    artists.append(line)

            # state markers
            xs, ys, labs = [], [], []
            for st in STATE_ORDER:
                node = states.get(st)
                if not node: continue
                tt = node.get("t")
                yy = node.get("rssi")
                if tt is None:
                    continue
                if yy is None or not isinstance(yy, (int, float)) or yy > -60 or yy < -120:
                    yy = nearest_series_y(series, tt)
                if yy is None:
                    continue
                xs.append(tt/1000.0); ys.append(yy); labs.append(st)

            for xi, yi, lab in zip(xs, ys, labs):
                if lab == "PEAK":
                    scp = ax.scatter([xi], [yi], s=220, color=color, edgecolor='black', linewidths=1.5, marker='*', zorder=5)
                    artists.append(scp)
                    ann = ax.annotate(lab, (xi, yi), textcoords="offset points", xytext=(0, -14), ha='center',
                                      fontsize=9, color=color)
                    artists.append(ann)
                else:
                    scm = ax.scatter([xi], [yi], s=60, color=color, marker=mshape, zorder=4)
                    artists.append(scm)
                    ann = ax.annotate(lab, (xi, yi), textcoords="offset points", xytext=(0, -12), ha='center',
                                      fontsize=8, color=color)
                    artists.append(ann)

            # missing LOGGED but other states present -> right-edge miss tick
            if states.get("LOGGED") is None and (states.get("APPROACHING") or states.get("HERE")) and t_s:
                xe = max(t_s) + (ax.get_xlim()[1] - ax.get_xlim()[0]) * 0.01
                ye = ys[-1] if ys else (rssi[-1] if rssi else (YMIN + 5))
                miss = ax.scatter([xe], [ye], s=110, marker='x', color=color, linewidths=2.0, zorder=5)
                artists.append(miss)

            if artists:
                label_to_artists.setdefault(alg_name, []).extend(artists)

        ax.grid(True, ls="--", lw=0.5, alpha=0.45)
        ax.set_xlabel("time (s)")
        ax.set_ylabel("RSSI (dBm)")
        ax.set_title(f"{item['name']}  [{item['speed']} · {item['dist']}]  (rows={item['nrows']})", fontsize=10)

    # ---------- Build legend from what actually exists ----------
    legend_handles = []
    present_labels = list(label_to_artists.keys())

    # Put "Measured" first if present
    if "Measured" in present_labels:
        present_labels = ["Measured"] + [l for l in present_labels if l != "Measured"]

    for lbl in present_labels:
        if lbl == "Measured":
            # marker-only handle for clarity
            legend_handles.append(
                mlines.Line2D([], [], linestyle='None', marker='o', markerfacecolor='none',
                              markeredgecolor='tab:blue', markersize=6, label='Measured')
            )
        else:
            # color from ALG_STYLE if known, else infer from first plotted artist
            if lbl in ALG_STYLE:
                color = ALG_STYLE[lbl]["color"]
            else:
                color = _first_color_from_artists(label_to_artists[lbl], "#8B4513")
            legend_handles.append(
                mlines.Line2D([], [], color=color, linestyle='--', linewidth=1.8, label=lbl)
            )

    legend = fig.legend(
        handles=legend_handles,
        loc="upper center",
        ncols=min(6, len(legend_handles)),
        frameon=True,
        fontsize=9,
        borderaxespad=0.2
    )

    # hide everything except Measured at start (includes unknown/brown algs)
    initially_hidden_labels = {lbl for lbl in present_labels if lbl != "Measured"}
    attach_interactive_legend(fig, legend, label_to_artists, initially_hidden_labels)

    return fig

# -------------------- MAIN --------------------
def main():
    paths = sorted(glob.glob(os.path.join(DATA_DIR, "result_*.json")))
    if not paths:
        print("No result_*.json found in:", DATA_DIR)
        return

    items = load_results(paths)
    if not items:
        print("No valid results loaded from:", DATA_DIR)
        return

    fig = plot_all(items)
    plt.show()

if __name__ == "__main__":
    main()
