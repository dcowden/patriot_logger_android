import os, glob, csv
import pandas as pd
import matplotlib.pyplot as plt

# ==== CONFIG ====
DATA_DIR = "../app/src/test/resources/data_samples"   # adjust if needed
BASE_TS = 1761170000000  # subtract to shorten timestamps
CLOSE_PEAK_DBM = -78     # only used for optional labeling

# ==== HELPERS ====
def read_csv_any_header(path):
    """Read a calibration CSV, tolerant of different header names."""
    with open(path, "r", newline="") as f:
        reader = csv.reader(f)
        # find first non-empty header line
        header = None
        for row in reader:
            if row and any(cell.strip() for cell in row):
                header = [c.strip().lower() for c in row]
                break
        if header is None:
            return pd.DataFrame(columns=["time", "rssi"])
        # find timestamp & rssi columns
        def idx(names):
            for n in names:
                if n in header:
                    return header.index(n)
            return -1
        ts_i = idx(["timestamp", "timestampms", "ts", "time"])
        rssi_i = idx(["rssi"])
        if ts_i < 0 or rssi_i < 0:
            # fallback: pandas
            df = pd.read_csv(path)
            cols = {c.lower(): c for c in df.columns}
            ts_col = None
            for cand in ["timestamp", "timestampms", "ts", "time"]:
                if cand in cols:
                    ts_col = cols[cand]
                    break
            if ts_col is None or "rssi" not in cols:
                return pd.DataFrame(columns=["time", "rssi"])
            return pd.DataFrame({
                "time": pd.to_numeric(df[ts_col], errors="coerce") - BASE_TS,
                "rssi": pd.to_numeric(df[cols["rssi"]], errors="coerce")
            }).dropna()
        # manual parse
        times, rssis = [], []
        for row in reader:
            if not row:
                continue
            if len(row) <= max(ts_i, rssi_i):
                continue
            ts_s = row[ts_i].strip()
            rssi_s = row[rssi_i].strip()
            if not ts_s or not rssi_s:
                continue
            try:
                t = float(ts_s) - BASE_TS
                r = float(rssi_s)
                times.append(t)
                rssis.append(r)
            except ValueError:
                continue
        return pd.DataFrame({"time": times, "rssi": rssis})

# ==== MAIN ====
def main():
    files = sorted(glob.glob(os.path.join(DATA_DIR, "*.csv")))
    if not files:
        print(f"No CSV files found in {DATA_DIR}")
        return

    for path in files:
        fname = os.path.basename(path)
        df = read_csv_any_header(path)
        if df.empty:
            print(f"Skipping empty or unreadable file: {fname}")
            continue

        # stats (optional, for quick printout)
        peak = df["rssi"].max()
        avg = df["rssi"].mean()
        print(f"{fname:40s}  rows={len(df):4d}  peak={peak:6.1f}  avg={avg:6.1f}")

        # plot
        plt.figure(figsize=(8, 4))
        plt.title(fname, fontsize=11)
        plt.plot(df["time"], df["rssi"], alpha=0.2, lw=0.5, color="gray")  # faint connecting line
        plt.plot(df["time"], df["rssi"],
                 marker='o', markersize=3, lw=0,
                 mfc='none', mec='tab:blue', alpha=0.7)
        plt.xlabel("t (ms offset)")
        plt.ylabel("RSSI (dBm)")
        plt.grid(True, ls="--", lw=0.5, alpha=0.5)
        plt.ylim([-105, -70])
        plt.tight_layout()
        plt.show()  # one window per file

if __name__ == "__main__":
    main()
