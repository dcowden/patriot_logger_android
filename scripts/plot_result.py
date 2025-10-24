# plot_results.py
import os, glob, json, math, re
import matplotlib.pyplot as plt
import matplotlib.lines as mlines

DATA_DIR = r"C:\gitwork\patriot_logger_android\app"

RUN_MAX_ROWS   = 70
WALK_MIN_ROWS  = 140
CLOSE_PEAK_DBM = -78
MAX_SUBPLOTS_PER_FIG = 4  # ≤ 4 charts per figure

def guess_speed(n_rows):
    if n_rows <= RUN_MAX_ROWS: return "Run"
    if n_rows >= WALK_MIN_ROWS: return "Walk"
    return "Jog"

def guess_distance(peak_rssi):
    if peak_rssi is None: return "?"
    return "Close" if peak_rssi >= CLOSE_PEAK_DBM else "Far"

ALG_STYLE = {
    "EMA(alpha=0.3)": dict(color="tab:red",    m="^"),
    "MedianN(N=7)":   dict(color="tab:orange", m="s"),
    "Kalman(Q=4.0,R=16.0)": dict(color="tab:green",  m="D"),
    "Median→EMA FSM (N=5, α=0.3, thrA=-90, thrH=-85, rise=3, fall=2, hyst=2dB)":
        dict(color="tab:purple", m="o"),
}
STATE_ORDER = ("APPROACHING", "HERE", "LOGGED", "PEAK")

def dynamic_ylim(series):
    ys = [p.get("rssi") for p in series if isinstance(p.get("rssi"), (int, float))]
    if not ys:
        return (-105, -70)
    y_min, y_max = min(ys), max(ys)
    if y_max > -60: y_max = -60
    return (min(y_min - 2, -105), max(y_max + 2, -70))

def nearest_series_y(series, t):
    if not series: return None
    best = min(series, key=lambda p: abs((p.get("t") or 0) - t))
    return best.get("rssi")

def load_results(paths):
    items = []
    for p in paths:
        try:
            with open(p, "r", encoding="utf-8") as fh:
                d = json.load(fh)
        except Exception:
            continue
        series = d.get("series", [])
        n = len(series)
        peak = None
        if series:
            vals = [v.get("rssi") for v in series if isinstance(v.get("rssi"), (int,float))]
            peak = max(vals) if vals else None
        sample_name = d.get("sampleFile", os.path.basename(p))  # e.g., calibration_data_...csv
        items.append({
            "path": p,
            "name": sample_name,
            "series": series,
            "algos":  d.get("algorithms", {}),
            "nrows":  n,
            "peak":   peak,
            "speed":  guess_speed(n),
            "dist":   guess_distance(peak),
        })
    return items

def chunks(seq, size):
    for i in range(0, len(seq), size):
        yield seq[i:i+size], (i//size + 1), math.ceil(len(seq)/size)

def page_algorithms_present(items_page):
    present = set()
    for item in items_page:
        for alg, states in (item.get("algos") or {}).items():
            has_line = bool(states.get("line"))
            has_pin = any(states.get(st) for st in STATE_ORDER)
            if has_line or has_pin:
                present.add(alg)
    return present

def legend_handles_for(algs):
    handles = []
    for alg in algs:
        sty = ALG_STYLE.get(alg, dict(color="tab:brown", m="x"))
        handles.append(
            mlines.Line2D([], [], color=sty["color"], linestyle='--', linewidth=1.6, label=alg)
        )
    return handles

def last_three_digits_before_csv(sample_name: str) -> str | None:
    """
    Extract the last 3 digits from the numeric run just before '.csv'.
    Example: 'calibration_data_1761170896.csv' -> '896'
    """
    m = re.search(r'(\d+)\.csv$', sample_name)
    if not m:  # fallback: try entire name for trailing digits
        m = re.search(r'(\d+)$', sample_name)
    if not m:
        return None
    digits = m.group(1)
    return digits[-3:] if len(digits) >= 3 else digits

def plot_page(speed, items_page, page_idx, page_count):
    rows = len(items_page)
    fig_h = max(3.4 * rows, 3.4)
    fig, axes = plt.subplots(rows, 1, figsize=(11, fig_h), sharex=False, sharey=False)
    if rows == 1:
        axes = [axes]

    start_idx = (page_idx - 1) * MAX_SUBPLOTS_PER_FIG + 1
    end_idx   = start_idx + rows - 1
    fig.suptitle(f"{speed} – samples {start_idx}–{end_idx}", fontsize=14)

    algs_present = page_algorithms_present(items_page)
    handles = legend_handles_for(sorted(algs_present))

    for ax, item in zip(axes, items_page):
        series = item["series"]
        t = [p["t"] for p in series]
        r = [p["rssi"] for p in series]

        # include last-3 suffix in title for quick ID
        suffix3 = last_three_digits_before_csv(item["name"]) or "???"
        ax.set_title(f"{item['name']} [id={suffix3}]  [{item['dist']}]  (rows={item['nrows']})", fontsize=10)

        if t and r:
            ax.plot(t, r, lw=0.6, alpha=0.3, color="gray")
            ax.plot(t, r, lw=0, marker='o', mfc='none', mec='tab:blue',
                    alpha=0.95, markersize=5.0)

        ymin, ymax = dynamic_ylim(series)
        ax.set_ylim((ymin, ymax))
        if t:
            tmin, tmax = min(t), max(t)
            pad = max(1.0, (tmax - tmin) * 0.05)
            ax.set_xlim((tmin, tmax + pad))

        for alg in sorted((item.get("algos") or {}).keys()):
            states = item["algos"][alg]
            sty = ALG_STYLE.get(alg, dict(color="tab:brown", m="x"))

            line = states.get("line")
            if line:
                lt = [p["t"] for p in line if p.get("t") is not None and p.get("v") is not None]
                lv = [p["v"] for p in line if p.get("t") is not None and p.get("v") is not None]
                if lt and lv:
                    ax.plot(lt, lv,
                            color=sty["color"],
                            linewidth=1.6,
                            linestyle='--',
                            marker='',
                            alpha=0.95)

            xs, ys, labs = [], [], []
            for st in STATE_ORDER:
                node = states.get(st)
                if not node: continue
                tt = node.get("t"); yy = node.get("rssi")
                if tt is None: continue
                if yy is None or not isinstance(yy, (int,float)) or yy > -60:
                    yy = nearest_series_y(series, tt)
                if yy is None: continue
                xs.append(tt); ys.append(yy); labs.append(st)

            if xs:
                for xi, yi, lab in zip(xs, ys, labs):
                    if lab == "PEAK":
                        ax.scatter([xi], [yi], s=180, color=sty["color"],
                                   edgecolor='black', linewidths=1.5,
                                   marker='*', zorder=5)
                        ax.annotate(lab, (xi, yi), textcoords="offset points",
                                    xytext=(0, -14), ha='center', fontsize=9, color=sty["color"])
                    else:
                        ax.scatter([xi], [yi], s=48, color=sty["color"],
                                   marker=sty["m"], zorder=3)
                        ax.annotate(lab, (xi, yi), textcoords="offset points",
                                    xytext=(0, -12), ha='center', fontsize=8, color=sty["color"])

        ax.grid(True, ls="--", lw=0.5, alpha=0.5)
        ax.set_xlabel("t (ms offset)")
        ax.set_ylabel("RSSI (dBm)")

    if handles:
        axes[-1].legend(handles=handles, loc="best", fontsize=8)
    fig.tight_layout(rect=[0, 0, 1, 0.95])
    return fig

def main():
    # === Edit this: last-three-digit IDs from the sample filename (before .csv).
    # e.g., calibration_data_...0896.csv -> use 896
    FOCUS_IDS = [896, 291, 894, 352]   # [] to plot ALL
    #FOCUS_IDS = [291]
    # ==================================

    paths = sorted(glob.glob(os.path.join(DATA_DIR, "result_*.json")))
    if not paths:
        print("No result_*.json found in:", DATA_DIR)
        return

    items = load_results(paths)

    # apply focus filter using sampleFile's last-3 digits
    if FOCUS_IDS:
        suffixes = {f"{i:03d}" for i in FOCUS_IDS}
        before = len(items)
        items = [it for it in items
                 if (last_three_digits_before_csv(it["name"]) or "") in suffixes]
        print(f"Filtered {len(items)} of {before} items for FOCUS_IDS={sorted(suffixes)}")
        if not items:
            print("Warning: no matches. Check the three-digit IDs vs sample filenames.")

    groups = {"Walk": [], "Jog": [], "Run": []}
    for it in items:
        sp = it["speed"]
        if sp not in groups:
            sp = "Run" if sp.lower().startswith("run") else sp
        groups.setdefault(sp, []).append(it)
    for k in groups:
        groups[k] = sorted(groups[k], key=lambda x: x["name"])

    for sp in ("Walk", "Jog", "Run"):
        g = groups.get(sp, [])
        if not g:
            continue
        total_pages = math.ceil(len(g) / MAX_SUBPLOTS_PER_FIG)
        for page_items, page_idx, _ in chunks(g, MAX_SUBPLOTS_PER_FIG):
            plot_page(sp, page_items, page_idx, total_pages)

    plt.show()

if __name__ == "__main__":
    main()
