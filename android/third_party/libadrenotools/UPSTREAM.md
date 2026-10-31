Vendored from https://github.com/bylaws/libadrenotools at
8fae8ce254dfc1344527e05301e43f37dea2df80. Included lib/linkernsbypass is from
https://github.com/bylaws/liblinkernsbypass at
aa3975893d83ef1bc84c321ec60c65fbf1287887.

Their original licenses and source headers apply. Local changes reject a failed
custom-driver load instead of silently loading the system driver, and guard a
null Android loader extension-info pointer.

Zakuro Android also reserves the terminating NUL when formatting the three-digit
unique SONAME prefix; the original three-byte buffer truncated its last digit.
