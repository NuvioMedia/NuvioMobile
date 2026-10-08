import re

with open('/Users/tanvir/.gemini/antigravity/brain/eb7bb0ae-1008-4d89-bcad-866ad75ba2c3/verify_bn_localization.py', 'r') as f:
    content = f.read()

content = content.replace(
    "('composeApp/src/commonMain/composeResources/values/strings_mdblist.xml', 'composeApp/src/commonMain/composeResources/values-bn/strings_mdblist.xml', 'MDBList Strings')",
    "('composeApp/src/commonMain/composeResources/values/strings_mdblist.xml', 'composeApp/src/commonMain/composeResources/values-bn/strings_mdblist.xml', 'MDBList Strings'),\n            ('composeApp/src/commonMain/composeResources/values/strings_servers.xml', 'composeApp/src/commonMain/composeResources/values-bn/strings_servers.xml', 'Server Strings')"
)

with open('/Users/tanvir/.gemini/antigravity/brain/eb7bb0ae-1008-4d89-bcad-866ad75ba2c3/verify_bn_localization.py', 'w') as f:
    f.write(content)
