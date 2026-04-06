CS 640 Lab 4 - Testing Guide

Prerequisites
=============
- Mininet VM with Java, POX, and ant installed
- Lab4 compiled: cd lab4 && javac -d src src/TCPend.java
- Router JAR built: cd lab4/mininet_test && ant


Step 1: Start Mininet
=====================
Terminal 1:

    cd /home/mininet/CS640-Labs/lab4/mininet_test
    sudo ./run_mininet.py topos/single_rt.topo -a


Step 2: Start POX Controller
=============================
Terminal 2:

    cd /home/mininet/CS640-Labs/lab4/mininet_test
    ./run_pox.sh


Step 3: Start Virtual Router (with 5% packet drop)
===================================================
Terminal 3:

    cd /home/mininet/CS640-Labs/lab4/mininet_test
    java -jar VirtualNetwork.jar -v r1 -r rtable.r1 -a arp_cache

Note: To change drop rate, edit Router.java line:
    if (dropRandom.nextInt(100) < 5)    // change 5 to desired percentage
Then rebuild: ant clean && ant


Step 4: Open Host Terminals
===========================
In mininet console (Terminal 1):

    xterm h1 h2


Step 5: Run Receiver (h2 xterm)
===============================
    cd /home/mininet/CS640-Labs/lab4
    java -cp src TCPend -p 12345 -m 1000 -c 5 -f /tmp/received.bin

The receiver will block waiting for connection - this is normal.


Step 6: Create Test File and Run Sender (h1 xterm)
===================================================
Small file (25KB):

    dd if=/dev/urandom of=/tmp/testfile.bin bs=1024 count=25
    cd /home/mininet/CS640-Labs/lab4
    java -cp src TCPend -p 12346 -s 10.0.2.102 -a 12345 -f /tmp/testfile.bin -m 1000 -c 5

Medium file (500KB):

    dd if=/dev/urandom of=/tmp/testfile_med.bin bs=1024 count=500
    java -cp src TCPend -p 12346 -s 10.0.2.102 -a 12345 -f /tmp/testfile_med.bin -m 1000 -c 5

Large file (5MB):

    dd if=/dev/urandom of=/tmp/testfile_lg.bin bs=1024 count=5000
    java -cp src TCPend -p 12346 -s 10.0.2.102 -a 12345 -f /tmp/testfile_lg.bin -m 1000 -c 5


Step 7: Verify Transfer
========================
On either h1 or h2 xterm:

    sha256sum /tmp/testfile.bin /tmp/received.bin

Both checksums must match.


Network Topology
================
    h1 (10.0.1.101) --- r1 --- h2 (10.0.2.102)
                         |
                        h3 (10.0.3.103)

- h1 sends to h2 at IP 10.0.2.102
- h2 sends to h1 at IP 10.0.1.101
- Router r1 forwards packets between subnets


Command Line Reference
======================
Sender:
    java TCPend -p <local_port> -s <receiver_IP> -a <receiver_port> -f <file_to_send> -m <mtu> -c <window_size>

Receiver:
    java TCPend -p <listen_port> -m <mtu> -c <window_size> -f <output_file>

Parameters:
    -p  local port number
    -s  remote IP (sender mode flag)
    -a  remote port
    -f  file path (input for sender, output for receiver)
    -m  MTU in bytes (max data per packet = mtu - 24 byte header)
    -c  sliding window size in number of segments


Output Format
=============
Each packet logged as:
    <snd/rcv> <time> <S> <A> <F> <D> <seq> <bytes> <ack>

Flags: S=SYN, A=ACK, F=FIN, D=Data, -=not set

Final stats line:
    <data_bytes> <packets> <out_of_seq> <checksum_errors> <retransmissions> <dup_acks>


Local Testing (without Mininet)
===============================
You can also test locally without mininet:

Terminal 1 (receiver):
    cd /home/mininet/CS640-Labs/lab4
    java -cp src TCPend -p 12345 -m 1000 -c 5 -f /tmp/received.bin

Terminal 2 (sender):
    cd /home/mininet/CS640-Labs/lab4
    java -cp src TCPend -p 12346 -s 127.0.0.1 -a 12345 -f /tmp/testfile.bin -m 1000 -c 5

Note: Local testing won't exercise retransmission since there's no packet loss.


Cleanup
=======
- In mininet console: exit
- Kill POX: Ctrl+C in Terminal 2
- Kill router: Ctrl+C in Terminal 3
- Remove test files: rm /tmp/testfile*.bin /tmp/received*.bin


Troubleshooting
===============
1. "pox.py not found": Run from mininet_test dir, ensure symlink exists:
       ln -sf /home/mininet/pox /home/mininet/CS640-Labs/lab4/mininet_test/pox

2. Receiver stuck: Normal - it's waiting for sender connection.

3. Sender times out: Check that POX and router are running, and use correct IP (10.0.2.102 for h2).

4. Port in use: Change port numbers or wait a moment for OS to release them.

5. Rebuild router after changing drop rate:
       cd mininet_test && ant clean && ant
