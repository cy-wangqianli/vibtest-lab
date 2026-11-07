#!/usr/bin/env python3
"""第二轮仿真：A 线=200Hz 双通道；B 线=静默恢复。"""
import random, sys

def synth(events, dur_s, amp, pulse_ms, gap_ms, sr, double_peak=False, jitter=False,
          noise=0.35, silence_from=None, silence_s=0):
    n = int(dur_s * sr)
    sig = [random.gauss(0, noise) for _ in range(n)]
    t_ms = 300.0
    for i in range(events):
        if silence_from is not None:
            s0 = silence_from * 1000.0
            if s0 <= t_ms < s0 + silence_s * 1000.0:
                t_ms += gap_ms
                continue
        start = int(t_ms / 1000 * sr)
        k = max(1, int(pulse_ms / 1000 * sr))
        for j in range(k):
            x = start + j
            if 0 <= x < n:
                sig[x] += amp * (0.5 - 0.5 * (2 * j / max(1, k - 1) - 1) ** 2)
        if double_peak:
            s2 = start + int(0.045 * sr)
            for j in range(k):
                x = s2 + j
                if 0 <= x < n:
                    sig[x] += amp * 0.9
        g = gap_ms * ((0.55 if i % 2 == 0 else 1.9) if jitter else 1.0)
        t_ms += g
    return sig

def level(sig, sr):
    lp = 0.0; floor = 0.0; last = -1e9; hits = []
    for i, v in enumerate(sig):
        lp += 0.35 * (v - lp)
        floor += 0.004 * (lp - floor)
        thr = max(0.9, floor * 2.0)
        t = i / sr * 1000.0
        if lp > thr and t - last > 320:
            last = t; hits.append(i)
    return hits

def dual(sig, sr):
    """慢通道判步 + 快通道（原始差分）判短脉冲，两通道共享去抖"""
    lp = 0.0; floor = 0.0; prev = 0.0; df = 0.0
    last = -1e9; hits = []
    for i, v in enumerate(sig):
        lp += 0.35 * (v - lp)
        floor += 0.004 * (lp - floor)
        d = v - prev; prev = v
        df += 0.004 * (abs(d) - df)
        slow = lp > max(0.9, floor * 2.0)
        fast = d > max(1.1, df * 2.0)
        t = i / sr * 1000.0
        if (slow or fast) and t - last > 320:
            last = t; hits.append(i)
    return hits

def run_a():
    print(f"{'样本':<8}{'事件':>5}{'100Hz 单通道漏':>16}{'200Hz 双通道漏':>16}{'双通道多检':>12}")
    print("-" * 60)
    for mode, kw in [("正常", {}), ("短脉冲", dict(amp=4.8, pulse_ms=15)),
                     ("抖动", dict(jitter=True)), ("双峰", dict(double_peak=True))]:
        tot = miss1 = miss2 = extra = 0
        for _ in range(10):
            s100 = synth(12, 18, kw.get("amp", 12), kw.get("pulse_ms", 120), 600, 100,
                         kw.get("double_peak", False), kw.get("jitter", False))
            s200 = synth(12, 18, kw.get("amp", 12), kw.get("pulse_ms", 120), 600, 200,
                         kw.get("double_peak", False), kw.get("jitter", False))
            a = level(s100, 100); b = dual(s200, 200)
            tot += 12
            miss1 += max(0, 12 - len(a)); miss2 += max(0, 12 - len(b))
            extra += max(0, len(b) - 12)
        print(f"{mode:<8}{tot:>5}{miss1:>16}{miss2:>16}{extra:>12}")

def run_b():
    print("静默 20 秒后恢复：记录恢复用时与静默期误检")
    print(f"{'试验':<6}{'恢复用时(ms)':>14}{'静默期误检':>12}")
    for k in range(5):
        sig = synth(24, 40, 12, 120, 600, 200, silence_from=8, silence_s=20)
        hits = dual(sig, 200)
        quiet = [h for h in hits if 8.0 * 200 <= h < 28.0 * 200]
        resume = [h for h in hits if h >= 28.0 * 200]
        rec = (resume[0] - 28.0 * 200) / 200 * 1000 if resume else -1
        print(f"{k+1:<6}{rec:>14.0f}{len(quiet):>12}")

mode = sys.argv[1] if len(sys.argv) > 1 else "a"
if mode == "a":
    run_a()
else:
    run_b()
