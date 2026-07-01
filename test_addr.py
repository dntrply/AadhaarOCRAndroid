import re

text = """
Name:RAJESH KUMAR
Gender: MALE
Date of Birth: 15/08/1982
1234 5678 9012
पता: मकान नंबर 45, गली नंबर 3, लाजपत नगर, नई दिल्ली - 110024
ADDRESS: H.No 45, Street No 3, Lajpat Nagar, New Delhi - 110024
"""

lines = [line.strip() for line in text.split("\n") if line.strip()]

def extractAddress(lines):
    addressStartPattern = re.compile(r"(?i)^(?:address|पता)[:\s]+(.*)$")
    englishAddressStartPattern = re.compile(r"(?i)^address[:\s]+(.*)$")
    
    addressResult = ""
    foundLabel = False
    
    for line in lines:
        if not foundLabel:
            match = addressStartPattern.search(line)
            if match:
                foundLabel = True
                addressResult = match.group(1).strip()
        else:
            # Check if this line is actually the English Address label overriding the Hindi one
            engMatch = englishAddressStartPattern.search(line)
            if engMatch:
                addressResult = engMatch.group(1).strip() # Reset to English!
                continue
                
            if re.search(r"(?i)(?:name|gender|dob|uid|आधार|नाम|लिंग)", line) or \
               re.search(r"\d{4}[ \t]+\d{4}[ \t]+\d{4}", line):
                break
                
            if line:
                if addressResult:
                    addressResult += ", "
                addressResult += line
                
    return addressResult

print("Extracted Address:", extractAddress(lines))
