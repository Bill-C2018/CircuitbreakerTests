import requests
import threading
from time import sleep

def request1():
    
    x = 0
    while True:
        data = { "name": "bill"}
        result = requests.get("http://localhost:8080/ctest",params = data).text
        msg =  "bill " + str(x) + " : " + result
        x = x + 1
        print(msg)
        sleep(0.05)

def request2():
    
    x = 0
    while True:
        data2 = {"name": "bob"} 
        result = requests.get("http://localhost:8080/ctest",params = data2).text
        msg =  "bob " + str(x) + " : " + result
        x = x + 1
        print(msg)
        sleep(0.05)
        
user1 = threading.Thread(target=request1, args=[])
user2 = threading.Thread(target=request2, args=[])

user2.start()
user1.start()
       