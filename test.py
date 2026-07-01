import re

text = """31TTR
GOVERNMENT OF INDIAE
Name:RAJESH KUMAR
Gender: yoq/ MALE
Date of Birth: 15/08/1982
1234 5678 9012
AADHAAR
4T: HO[H T 45, 1clt HR 3, GIvyG R, M fc - 110024
ADDRESS: H.No 45, Street No 3, Lajpat Nagar, New Delhi - 110024"""

flexiblePattern = re.compile(r"(?<![A-Za-z0-9])([0-9OolISB]{4})[ \t]+([0-9OolISB]{4})[ \t]+([0-9OolISB]{4})(?![A-Za-z0-9])")
matches = list(flexiblePattern.finditer(text))

print("matches found:", len(matches))
for match in matches:
    print("Match:", match.group(0))

exact12Pattern = re.compile(r"(?<!\d)(\d[ \t-]*){12}(?!\d)")
exactMatches = list(exact12Pattern.finditer(text))
print("exactMatches found:", len(exactMatches))
for match in exactMatches:
    print("Exact Match:", match.group(0))
