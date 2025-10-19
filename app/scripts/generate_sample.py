import csv
import math
import random
from datetime import datetime

# Parameters
duration_seconds = 40
sample_interval_ms = 50  # Sample every 50ms
num_points = (duration_seconds * 1000) // sample_interval_ms  # 800 points
start_time_ms = 1729353600000  # Starting timestamp in milliseconds
tag_id = 123

# RSSI parameters
rssi_far = -90  # Starting RSSI when far away
rssi_close = -50  # Peak RSSI when closest
noise_stddev = 3.5  # Standard deviation for noise (typical for nRF52840 with chip antenna)

# EMA parameters
alpha = 0.1

# Generate the approach and departure curve
def generate_rssi_curve(num_points, rssi_far, rssi_close):
    """Generate a smooth RSSI curve simulating approach and departure"""
    curve = []
    peak_index = num_points // 2  # Peak at midpoint

    for i in range(num_points):
        # Use a Gaussian-like curve for natural approach/departure
        # Distance from peak (normalized)
        x = (i - peak_index) / (num_points / 4.0)

        # Gaussian curve: closer to peak = stronger signal (less negative RSSI)
        signal_strength = math.exp(-0.5 * x * x)

        # Map to RSSI range (more negative = weaker)
        rssi = rssi_far + (rssi_close - rssi_far) * signal_strength
        curve.append(rssi)

    return curve

# Generate base RSSI curve
base_rssi_curve = generate_rssi_curve(num_points, rssi_far, rssi_close)

# Add noise and compute smoothed RSSI
data = []
smoothed_rssi = base_rssi_curve[0]  # Initialize EMA with first value

for i in range(num_points):
    timestamp_ms = start_time_ms + (i * sample_interval_ms)

    # Add Gaussian noise to simulate real beacon behavior
    raw_rssi = base_rssi_curve[i] + random.gauss(0, noise_stddev)

    # Occasionally add larger spikes (typical for BLE)
    if random.random() < 0.1:  # 10% chance of spike
        raw_rssi += random.uniform(-8, 8)

    # Round to integer (RSSI is always integer)
    raw_rssi = round(raw_rssi)

    # Compute smoothed RSSI using EMA: S_t = α * X_t + (1 - α) * S_{t-1}
    smoothed_rssi = alpha * raw_rssi + (1 - alpha) * smoothed_rssi

    data.append({
        'timestamp': timestamp_ms,
        'tagid': tag_id,
        'rssi': raw_rssi,
        'smoothedrssi': round(smoothed_rssi, 2)
    })

# Write to CSV file
output_file = 'sample_walk_by.csv'
with open(output_file, 'w', newline='') as csvfile:
    fieldnames = ['timestamp', 'tagid', 'rssi', 'smoothedrssi']
    writer = csv.DictWriter(csvfile, fieldnames=fieldnames)

    writer.writeheader()
    for row in data:
        writer.writerow(row)

print(f"Generated {num_points} data points over {duration_seconds} seconds")
print(f"Sample interval: {sample_interval_ms}ms")
print(f"Sample saved to: {output_file}")
print(f"\nFirst 5 rows:")
for i in range(5):
    print(f"  {data[i]}")
print(f"\nPeak area (rows {num_points//2-2} to {num_points//2+2}):")
for i in range(num_points//2-2, num_points//2+3):
    print(f"  {data[i]}")
print(f"\nLast 5 rows:")
for i in range(-5, 0):
    print(f"  {data[i]}")
print(f"\nTotal file size: ~{len(data)} rows")