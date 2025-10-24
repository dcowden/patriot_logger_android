import os, glob, csv, math
import pandas as pd
import matplotlib.pyplot as plt

# ---- config ----
DATA_DIR = "../app/src/test/resources/data_samples"   # <- change if yours is different
BASE_TS = 1761170000000                        # subtract from timestamps to shorten
# thresholds (tweak if needed)
RUN_MAX_ROWS = 70        # rows <= 70 => Run
WALK_MIN_ROWS = 140      # rows >= 140 => Walk, else Jog
CLOSE_PEAK_DBM = -78     # peak >= -78 dBm => Close, else Far

# ---- helpers ----
def read_csv_any_header(path):
    """
    Robust CSV reader:
      - case-insensitive headers
      - expects at least timestamp(+/- 'ms') and rssi columns
    Returns DataFrame with columns: time, rssi
    """
    with open(path, "r", newline="") as f:
        reader = csv.reader(f)
        # read first non-empty line as header
        header = None
        for row in reader:
            if row and any(cell.strip() for cell in row):
                header = [c.strip().lower() for c in row]
                break
        if header is None:
            return pd.DataFrame(columns=["time", "rssi"])
        # figure out indexes
        def idx(names):
            for n in names:
                if n in header: return header.index(n)
            return -1
        ts_i   = idx(["timestamp", "timestampms", "ts", "time"])
        rssi_i = idx(["rssi"])
        if ts_i < 0 or rssi_i < 0:
            # try pandas as a fallback if a weird header line slipped through
            df = pd.read_csv(path)
            cols = {c.lower(): c for c in df.columns}
            ts_col = None
            for cand in ["timestamp", "timestampms", "ts", "time"]:
                if cand in cols: ts_col = cols[cand]; break
            if ts_col is None or "rssi" not in cols:
                return pd.DataFrame(columns=["time", "rssi"])
            return pd.DataFrame({
                "time": pd.to_numeric(df[ts_col], errors="coerce") - BASE_TS,
                "rssi": pd.to_numeric(df[cols["rssi"]], errors="coerce")
            }).dropna()
        # read remaining rows
        times, rssis = [], []
        for row in reader:
            if not row: continue
            # pad short rows
            if len(row) <= max(ts_i, rssi_i): continue
            ts_s = row[ts_i].strip()
            rssi_s = row[rssi_i].strip()
            if not ts_s or not rssi_s: continue
            try:
                t = float(ts_s) - BASE_TS
                r = float(rssi_s)
                times.append(t)
                rssis.append(r)
            except ValueError:
                continue
        return pd.DataFrame({"time": times, "rssi": rssis})

def classify_speed(n_rows):
    if n_rows <= RUN_MAX_ROWS:
        return "Run"
    if n_rows >= WALK_MIN_ROWS:
        return "Walk"
    return "Jog"

def classify_distance(peak_rssi):
    # RSSI is negative; closer = less negative (e.g., -75)
    return "Close" if peak_rssi >= CLOSE_PEAK_DBM else "Far"

def split_run_hand_shoe(files_with_first_ts):
    """
    Given list of (fname, first_ts), return set of filenames considered 'Run (Shoe)'
    by splitting at the median first_ts (later half => shoe).
    """
    if not files_with_first_ts: return set()
    sorted_pairs = sorted(files_with_first_ts, key=lambda x: x[1])
    mid = len(sorted_pairs) // 2
    # later half -> shoe
    return set(fname for fname, _ in sorted_pairs[mid:])

# ---- load and classify all files ----
records = []  # one record per file
all_csvs = sorted(glob.glob(os.path.join(DATA_DIR, "*.csv")))
for fpath in all_csvs:
    df = read_csv_any_header(fpath)
    if df.empty:
        continue
    # basic stats
    n = len(df)
    peak = df["rssi"].max()  # e.g., -73 bigger than -85
    first_ts = df["time"].iloc[0]
    speed = classify_speed(n)
    dist = classify_distance(peak)
    records.append({
        "file": os.path.basename(fpath),
        "path": fpath,
        "n": n,
        "peak": peak,
        "first_ts": first_ts,
        "speed": speed,
        "distance": dist,
        "df": df
    })

# refine "Run" into "Run (Hand)" vs "Run (Shoe)" based on time ordering
run_files = [(r["file"], r["first_ts"]) for r in records if r["speed"] == "Run"]
shoe_set = split_run_hand_shoe(run_files)
for r in records:
    if r["speed"] == "Run":
        r["speed"] = "Run (Shoe)" if r["file"] in shoe_set else "Run (Hand)"

# ---- build trellis grid ----
row_labels = ["Walk", "Jog", "Run (Hand)", "Run (Shoe)"]
col_labels = ["Close", "Far"]

# sanity print
counts = {(rl, cl): 0 for rl in row_labels for cl in col_labels}
for r in records:
    key = (r["speed"], r["distance"])
    if key in counts:
        counts[key] += 1
print("Files per facet:")
for rl in row_labels:
    print({cl: counts[(rl, cl)] for cl in col_labels})

# plot
fig, axes = plt.subplots(len(row_labels), len(col_labels), figsize=(10, 8), sharex=True, sharey=True)
fig.suptitle("RSSI vs Time by Speed and Distance", fontsize=14)

for r in records:
    rr = row_labels.index(r["speed"])
    cc = col_labels.index(r["distance"])
    ax = axes[rr, cc]

    # Plot faint connecting line
    ax.plot(r["df"]["time"], r["df"]["rssi"], alpha=0.2, lw=0.5, color="gray")

    # Plot data points as small circles
    ax.plot(
        r["df"]["time"], r["df"]["rssi"],
        marker='o', markersize=2.5, linewidth=0,
        alpha=0.6, color="tab:blue"
    )

    ax.set_title(f"{r['speed']} – {r['distance']}")

# cosmetics
for ax in axes.flat:
    ax.grid(True, ls="--", lw=0.5, alpha=0.5)
    ax.set_xlabel("t (ms offset)")
    ax.set_ylabel("RSSI (dBm)")
    ax.set_ylim([-105, -70])

plt.tight_layout(rect=[0, 0, 1, 0.95])
plt.show()
