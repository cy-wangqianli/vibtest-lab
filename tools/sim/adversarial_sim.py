#!/usr/bin/env python3
"""离线仿真：把发生器几种波形合成加速度序列，喂给两版检测器，统计漏检/多检。
   注意：这是模型仿真，不是真机数据。"""
import random, statistics

SR = 100  # Hz

def synth(events, dur_s, amp, pulse_ms, gap_ms, double_peak=False, jitter=False, noise=0.35):
    """events: 脉冲个数；返回 100Hz 的 linear-accel 序列（m/s²）"""
    n = int(dur_s * SR)
    sig = [random.gauss(0, noise) for _ in range(n)]
    t_ms = 300.0
    for i in range(events):
        start = int(t_ms / 1000 * SR)
        k = max(1, int(pulse_ms / 1000 * SR))
        for j in range(k):
            x = start + j
            if 0 <= x < n:
                sig[x] += amp * (0.5 - 0.5 * (2 * j / max(1, k - 1) - 1) ** 2)   # 抛物线包络
        if double_peak:
            s2 = start + int(0.045 * SR)
            for j in range(k):
                x = s2 + j
                if 0 <= x < n:
                    sig[x] += amp * 0.9
        g = gap_ms
        if jitter:
            g = gap_ms * (0.55 if i % 2 == 0 else 1.9)
        t_ms += g
    return sig

def detect(sig, hardened):
    lp = 0.0
    floor = 0.0
    last = -10 ** 9
    armed = True
    last_gap = 0.0
    hits = []
    for i, v in enumerate(sig):
        lp += 0.35 * (v - lp)
        floor += 0.004 * (lp - floor)
        thr = max(1.0, floor * 2.5)
        if hardened:
            if not armed and lp < thr * 0.6:
                armed = True
            refr = 260.0
            if last_gap > 0:
                refr = max(180.0, min(320.0, last_gap * 0.5))
        else:
            armed = True
            refr = 260.0
        t = i / SR * 1000.0
        if armed and lp > thr and (t - last) > refr:
            armed = False
            if last > 0:
                last_gap = t - last
            last = t
            hits.append(i)
    return hits

def trial(mode, n_events=12):
    if mode == "正常":
        return synth(n_events, 18, 12, 120, 600)
    if mode == "短脉冲":
        return synth(n_events, 18, 4.8, 15, 600)
    if mode == "抖动":
        return synth(n_events, 18, 12, 120, 600, jitter=True)
    if mode == "双峰":
        return synth(n_events, 18, 12, 120, 600, double_peak=True)
    raise ValueError(mode)

print(f"{'样本':<8}{'事件':>4}{'基线 检出':>10}{'基线 漏检':>10}{'加固 检出':>10}{'加固 漏检':>10}")
print("-" * 56)
for mode in ["正常", "短脉冲", "抖动", "双峰"]:
    tot = miss_b = miss_h = 0
    for _ in range(10):
        sig = trial(mode)
        b = detect(sig, False)
        h = detect(sig, True)
        tot += 12
        miss_b += max(0, 12 - len(b))
        miss_h += max(0, 12 - len(h))
    print(f"{mode:<8}{tot:>4}{tot-miss_b:>10}{miss_b:>10}{tot-miss_h:>10}{miss_h:>10}")

print()
print("说明：事件 = 设定的落点次数；检出 = 检测器报出的步数（超过事件数即为多检）。")
print("正常/短脉冲/抖动/双峰 各跑 10 组，每组 12 个事件，合计 120。")
