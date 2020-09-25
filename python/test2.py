import requests

for x in range(0,100):
    data = { "name": "bill"}
    data2 = {"name": "bob"}
#    result = requests.get("http://localhost:8080/test",params = data).text
#    msg =  "bill " + str(x) + " : " + result
#   print(msg)
    result = requests.get("http://localhost:8080/test",params = data2).text
    msg =  "bob " + str(x) + " : " + result
    print(msg)
