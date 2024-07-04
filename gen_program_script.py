bits = [
    "/home/amax/hhj/p4nic_new/p4nic_new.runs/impl_1/WorkerNICTop",
    "/home/amax/hhj/p4nic_new/p4nic_new.runs/impl_1/WorkerNICTop"
]

cards = [
    # ("xcu280_u55c_0", "21770205K02VA", 0),
    ("xcu280_u55c_0", "21770202700VA", 0),
    # ("xcu280_u55c_0_2", "21760297J03YA", 0),
    ("xcu280_u55c_0_1", "21770205K022A", 0),
    # ("xcu280_u55c_0_4", "21770205K02TA", 0),
    # ("xcu280_u55c_0_5", "21760297J03CA", 0),
    ("xcu280_u55c_0_2", "21770205K02JA", 0),
    ("xcu280_u55c_0_3", "21770205K01EA", 0),
    # ("xcu50_u55n_0", "00500208D1BAA", 1)
]

script = "connect_hw_server -url 192.168.189.16:3121 -allow_non_jtag"

for card in cards:
    script += "\n" + f"""
open_hw_target {{192.168.189.16:3121/xilinx_tcf/Xilinx/{card[1]}}}
current_hw_device [get_hw_devices {card[0]}]
refresh_hw_device -update_hw_probes false [lindex [get_hw_devices {card[0]}] 0]
set_property PROBES.FILE {{{bits[card[2]]}.ltx}} [get_hw_devices {card[0]}]
set_property FULL_PROBES.FILE {{{bits[card[2]]}.ltx}} [get_hw_devices {card[0]}]
set_property PROGRAM.FILE {{{bits[card[2]]}.bit}} [get_hw_devices {card[0]}]
program_hw_devices [get_hw_devices {card[0]}]
refresh_hw_device [lindex [get_hw_devices {card[0]}] 0]
close_hw_target {{192.168.189.16:3121/xilinx_tcf/Xilinx/{card[1]}}}
    """

with open("program.txt", "w") as f:
    f.write(script)