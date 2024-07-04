import random

n = 2048  # Number of random numbers to generate
m = 20000  # Upper limit for random numbers

# Generate n random numbers between 1 and m-1
random_numbers = [random.randint(1, m-1) for _ in range(n)]

# Sort the numbers from small to large
random_numbers.sort()

# Convert the numbers to 32-bit hexadecimal and pad with zeros to 8 characters
hex_numbers = [format(num, '08x') for num in random_numbers]

# Modify the existing code to generate 16 numbers per line
with open('a2.txt', 'w') as f:
    for i in range(0, len(hex_numbers), 16):
        line = ''.join(sorted(hex_numbers[i:i+16], reverse=True))
        f.write(line + '\n')

with open('a2.txt', 'a') as f:
    f.write('f' * 128 + '\n')