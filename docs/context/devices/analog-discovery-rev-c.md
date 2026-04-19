# Digilent Analog Discovery (Rev C)

## Identity

- **Vendor:** Digilent (a National Instruments company)
- **Model:** Analog Discovery, board revision C (final revision of the original device)
- **Canonical slug:** `analog-discovery-rev-c`
- **Status:** End-of-life as of 2017. Digilent recommends the [Analog Discovery 3](https://digilent.com/reference/test-and-measurement/analog-discovery-3/start) for new designs. Rev C hardware is still in active use on lab benches.
- **USB:** FTDI FT232H bridge + Xilinx Spartan-6 FPGA. The FPGA is configured at runtime with a bitstream Digilent ships with WaveForms (or our `pyftdi` driver's bundled copy).

## Capabilities

| Instrument | Spec |
|---|---|
| Oscilloscope | 2 channels, 14-bit ADC, up to 100 MS/s sustained, ±25 V with 10× probe (±5 V direct) |
| Arbitrary Waveform Gen | 2 channels, 14-bit DAC, ±5 V out, up to 12.5 MS/s |
| Power Supply (V+, V−) | ±5 V programmable rails, 12-bit DAC each, up to 700 mA per rail |
| Digital I/O | 16 pins, 3.3 V LVCMOS (5 V-tolerant inputs), up to 100 MS/s |
| Protocol decoders (via DIO) | UART, I²C, SPI, CAN — not implemented in our in-tree driver yet |
| Spectrum analyzer | WaveForms GUI feature; not exposed to Python SDK |

Buffer sizes: 16 ksamples per channel shared between scope and AWG by default; can be reallocated.

## Pin layout

The Analog Discovery ships with a flying-lead cable. The 2×15 header (30 pins) maps to the board as follows:

| Header pin | Wire color | Function | Notes |
|:-:|---|---|---|
| 1 | Orange | Scope CH1 +         | Differential input (with pin 2 as CH1−) |
| 2 | Orange / white | Scope CH1 −  | Ground for single-ended use |
| 3 | Blue | Scope CH2 +         | Differential input |
| 4 | Blue / white | Scope CH2 −   | Ground for single-ended use |
| 5 | Yellow | AWG CH1 out       | ±5 V range |
| 6 | Yellow / white | AWG CH1 GND | |
| 7 | Yellow | AWG CH2 out       | ±5 V range |
| 8 | Yellow / white | AWG CH2 GND | |
| 9 | Red    | V+ supply         | 0 to +5 V, up to 700 mA |
| 10 | Black | V+ GND            | |
| 11 | White | V− supply         | 0 to −5 V, up to 700 mA |
| 12 | Black | V− GND            | |
| 13 | Green | Ext trigger 1     | DIO-addressable, 3.3 V LVCMOS |
| 14 | Purple | Ext trigger 2    | |
| 15 | Black | GND               | Bulk ground |
| 16 | Pink   | DIO 0            | LVCMOS 3.3 V, 5 V-tolerant input |
| 17 | Pink   | DIO 1            | |
| 18 | Pink   | DIO 2            | |
| ... | ... | DIO 3–13 | Same electricals |
| 30 | Pink   | DIO 15           | |

Full pinout PDF: <https://digilent.com/reference/_media/analog_discovery:analog_discovery_pinout.pdf>.

## Safety limits

- **Never exceed ±25 V on scope inputs even with a 10× probe.** Direct input is ±5 V.
- **Do not source more than 700 mA per PSU rail.** Brownout protection kicks in before damage, but repeated clamp events shorten the reference's life.
- **DIO inputs are 5 V-tolerant, but outputs are strictly 3.3 V.** Driving a 5 V-only logic input from DIO works only if the input has a low logic-high threshold (< 2 V).
- **The FPGA must be configured before use.** A freshly-connected device has uninitialized I/O — do not connect hot.
- **Ground loops:** bench PSUs, scope, DUT, and AD share a common ground. Floating setups will float the AD too; isolated probes are not in the box.

## Common operations

Library in use by this plugin: `python/drivers/analog_discovery/` (pyftdi + bundled bitstream, zero external deps beyond pip).

```python
from analog_discovery.driver import AnalogDiscovery

ad = AnalogDiscovery(bitstream_path="…/ad_bitstream.bit")
ad.connect()                                    # loads FPGA, exposes instruments
try:
    ad.psu.set_voltage(rail="v_plus", volts=3.3)
    ad.psu.enable(rail="v_plus")

    ad.scope.configure_channel(
        channel=0, v_range=5.0, sample_rate=1_000_000, samples=8192)
    ad.scope.arm_trigger(channel=0, level=1.5, edge="rising")
    waveform = ad.scope.read_samples(channel=0)

    ad.awg.set_waveform(channel=0, shape="sine",
                        freq_hz=1_000, amplitude_v=1.0)
    ad.awg.enable(channel=0)

    ad.dio.set_direction(mask=0x00FF)           # DIO 0..7 outputs
    ad.dio.write(0x55)                          # pattern on DIO 0..7
finally:
    ad.disconnect()                             # always disable PSU rails first
```

Typical bring-up sequence: `connect → configure instruments → arm/enable → read/trigger → disable → disconnect`. Don't skip the `finally` — leaving a PSU rail live between runs is how you cook a DUT.

## Oscilloscope bring-up validation

Before trusting any measurement from the scope, prove the physical link with the in-repo validator at `python/scripts/validate_scope.py`. It is intentionally written against `pydwf` (the vendor WaveForms SDK binding) rather than the in-tree `pyftdi` driver, so a pass confirms the **device + USB + drivers** are sound independent of our own driver stack.

### Wiring

Jumper **W1 → 1+** and **GND → 1−** on the AD breadboard (AWG channel 1 out looped to scope channel 1 differential input). No DUT, no external PSU, no DIO.

### Run

```bash
pip install pydwf numpy
SCOPE_CH=0 python -m scripts.validate_scope   # SCOPE_CH=1 uses 2+/2−
```

### The four progressive checks

Each prints `[PASS]` / `[FAIL]` / `[SKIP]` with the measured value, and the script exits on the first `FAIL` so the failing stage is obvious:

| # | Check | What it proves | Typical output |
|:-:|---|---|---|
| 1 | SDK import | `pydwf` is installed and the WaveForms runtime (`dwf.framework`) is on disk and loadable | `libdwf <version>` |
| 2 | Device enumerates | USB + Digilent drivers see at least one AD; FPGA is reachable | device name + serial |
| 3 | AnalogIO readback | Control path works without touching the analog front-end — reads the AD's own USB rail via `analogIO.channelNodeStatus(2, 0)` | USB rail ≈ 5.0 V (PASS if 4.0 < usb_v < 5.5) |
| 4 | AWG → scope loopback | AWG emits a 1 kHz, 2 Vpp sine on W1; scope captures it on `SCOPE_CH` at 1 MS/s for 8192 samples (buffered, software-triggered via `DwfTriggerSource.None_`) | `v_pp = 2.0 V ± 0.2`, `f = 1000 Hz ± 50` |

### Interpreting failures

- **SDK import FAIL** → install WaveForms runtime (pydwf is just a binding; it needs the Digilent-shipped `dwf` shared library).
- **Device enumerates FAIL: "no Digilent devices found"** → USB cable, hub, or driver. Run WaveForms GUI as a cross-check; if that also can't see the device, it's not a software problem.
- **AnalogIO SKIP** → non-fatal; AD2/AD3 differences mean channel 2 node 0 is the USB rail readout, but some units expose different nodes. Not a blocker for the loopback test.
- **Scope capture FAIL: timed out** → the scope never reached `DwfState.Done` within 2 s of arming. Usually means the AWG isn't actually outputting (check W1 jumper), or USB bandwidth starved the capture (try a powered hub / different port).
- **Loopback amplitude FAIL (v_pp way off 2.0 V)** → wiring (open jumper), ground-reference issue (1− not tied to AWG GND), or a probe in series. Measure W1 directly with a DMM before blaming the scope.
- **Loopback frequency FAIL (zero-crossing count low or freq outside 950–1050 Hz)** → AWG is running but not at the programmed rate; check that nothing else (WaveForms GUI, another sidecar) is holding the device and reconfiguring it.

### Thresholds worth reusing

The tolerances in the script (`AMP_TOL = (1.8, 2.2)`, `FREQ_TOL = (950.0, 1050.0)`) are sane defaults for any self-loopback sanity check on this hardware at 1 kHz / 2 Vpp. Copy them verbatim into higher-level experiments unless you have a reason to tighten.

### Key API shapes (for new scripts that go direct to pydwf)

The validator is the canonical reference for these calls; mirror it when you need to bypass the in-tree driver:

```python
from pydwf import (
    DwfLibrary, DwfAcquisitionMode, DwfAnalogOutNode,
    DwfAnalogOutFunction, DwfState, DwfTriggerSource,
)
from pydwf.utilities import openDwfDevice

with openDwfDevice(DwfLibrary()) as hdwf:
    # AWG: note amplitude is PEAK, not peak-to-peak
    awg = hdwf.analogOut
    awg.nodeEnableSet(0, DwfAnalogOutNode.Carrier, True)
    awg.nodeFunctionSet(0, DwfAnalogOutNode.Carrier, DwfAnalogOutFunction.Sine)
    awg.nodeFrequencySet(0, DwfAnalogOutNode.Carrier, 1000.0)
    awg.nodeAmplitudeSet(0, DwfAnalogOutNode.Carrier, 1.0)     # → 2 Vpp
    awg.configure(0, True)

    scope = hdwf.analogIn
    scope.channelEnableSet(0, True)
    scope.channelRangeSet(0, 5.0)
    scope.frequencySet(1_000_000.0)
    scope.bufferSizeSet(8192)
    scope.acquisitionModeSet(DwfAcquisitionMode.Single)
    scope.triggerSourceSet(DwfTriggerSource.None_)       # software-timed
    scope.configure(False, True)                         # arm + start
    # poll scope.status(True) until DwfState.Done, then scope.statusData(0, 8192)
```

## Known gotchas

- **USB bandwidth is the real sample-rate ceiling** on a crowded hub. A dedicated USB port (or a powered USB-2 hub) reliably delivers 100 MS/s captures; shared ports drop samples silently.
- **Trigger edge matters more than you think.** "Rising at 1.5 V" triggers on a noisy slow edge even if the real event is a 100 ns pulse. Use `edge="rising"` + a tighter `level` or move to hardware-qualified triggers.
- **AWG and scope share the 16 ksample buffer.** Large scope captures reduce available AWG waveform memory. If the AWG goes silent mid-test, check the split.
- **PSU rails are NOT output-current-limited to the rated 700 mA.** They fold back aggressively below that if the regulator's thermal sense trips. If the rail sags, pull less current.
- **DIO reads on a moving pattern have ~10 ns jitter** due to sampling relative to the internal clock. For timing-critical reads, use the logic analyzer mode instead of polled `dio.read()`.
- **Bitstream version drift:** different AD Rev C units shipped with slightly different FTDI EEPROM settings. If `connect()` fails with a VID/PID mismatch, run the vendor's "AD Firmware Utility" once to re-flash the EEPROM. This is rare but does happen.
- **Do not run the WaveForms GUI and our sidecar simultaneously.** Both try to grab the USB handle; whoever gets there second hangs.

## Sources

- [Analog Discovery Technical Reference Manual (rev C)](https://digilent.com/reference/_media/analog_discovery:analog_discovery_rm.pdf) — fetched 2026-04-18, confidence 5 (official manufacturer doc)
- [Analog Discovery Pinout PDF (rev C)](https://digilent.com/reference/_media/analog_discovery:analog_discovery_pinout.pdf) — fetched 2026-04-18, confidence 5
- [Goodbye Original Analog Discovery](https://digilent.com/blog/goodbye-original-analog-discovery/) — fetched 2026-04-18, confidence 4 (vendor blog, EOL announcement)
- [WaveForms SDK Reference Manual](https://digilent.com/reference/_media/waveforms_sdk_reference_manual.pdf) — fetched 2026-04-18, confidence 5 (for `dwfpy` comparison)
- Benchy repo `docs/ANALOG_DISCOVERY_PINOUT.md` — 233 lines, internal ground-truth, confidence 4
- In-tree driver `python/drivers/analog_discovery/` — authoritative for the method surface we expose
