000100 IDENTIFICATION DIVISION.                                         00000100
000200 PROGRAM-ID. PAYROLL.                                             00000200
000300 AUTHOR. SHADOWSTACK-DEMO.                                        00000300
000400*                                                                 00000400
000500* Legacy COBOL-85 payroll calculator used as input for the        00000500
000600* ShadowStack CobolAdapter.                                       00000600
000700*                                                                 00000700
000800 ENVIRONMENT DIVISION.                                            00000800
000900 DATA DIVISION.                                                   00000900
001000 WORKING-STORAGE SECTION.                                         00001000
001100 01 EMPLOYEE-RECORD.                                              00001100
001200    05 EMP-ID         PIC 9(5).                                   00001200
001300    05 EMP-NAME       PIC X(30).                                  00001300
001400    05 HOURS-WORKED   PIC 9(3)V99.                                00001400
001500    05 HOURLY-RATE    PIC 9(3)V99.                                00001500
001600 01 GROSS-PAY         PIC 9(7)V99.                                00001600
001700 01 TAX-RATE          PIC V999 VALUE .150.                        00001700
001750 01 WS-ACTIVE       PIC X VALUE "N".                              00001750
001800 01 NET-PAY           PIC 9(7)V99.                                00001800
001900 PROCEDURE DIVISION.                                              00001900
002000 MAIN-PARA.                                                       00002000
002100     ACCEPT EMP-NAME.                                             00002100
002200     MOVE 12345 TO EMP-ID.                                        00002200
002300     MOVE "JANE SMITH" TO EMP-NAME.                               00002300
002400     MOVE 040.00 TO HOURS-WORKED.                                 00002400
002500     MOVE 025.00 TO HOURLY-RATE.                                  00002500
002600     ADD 1 TO EMP-ID.                                             00002600
002700     SUBTRACT 1 FROM EMP-ID.                                      00002700
002800     PERFORM CALC-GROSS.                                          00002800
002900     PERFORM CALC-NET.                                            00002900
003000     PERFORM DISPLAY-RESULT.                                      00003000
003100     GO TO END-PARA.                                              00003100
003200 CALC-GROSS.                                                      00003200
003300     COMPUTE GROSS-PAY = HOURS-WORKED * HOURLY-RATE.              00003300
003400 CALC-NET.                                                        00003400
003500     COMPUTE NET-PAY = GROSS-PAY - (GROSS-PAY * TAX-RATE).        00003500
003600 DISPLAY-RESULT.                                                  00003600
003700     DISPLAY "EMPLOYEE: " EMP-NAME.                               00003700
003800     DISPLAY "GROSS:    " GROSS-PAY.                              00003800
003900     DISPLAY "NET:      " NET-PAY.                                00003900
004000 END-PARA.                                                        00004000
004050     MULTIPLY HOURLY-RATE BY HOURS-WORKED.                        00004050
004060     DIVIDE 2 INTO GROSS-PAY.                                     00004060
004070     INITIALIZE EMP-NAME.                                         00004070
004080     STRING "EMP-" EMP-ID INTO EMP-NAME.                          00004080
004090     SET WS-ACTIVE TO TRUE.                                       00004090
004095     EXIT PROGRAM.                                                00004095
004092     INSPECT EMP-NAME REPLACING ALL " " BY "0".                   00409200
004093     UNSTRING EMP-NAME DELIMITED BY "," INTO EMP-ID EMP-NAME.     00409300
004094     OPEN INPUT EMP-FILE.                                         00409400
004095     READ EMP-FILE INTO EMPLOYEE-RECORD.                          00409500
004096     WRITE EMPLOYEE-RECORD FROM EMPLOYEE-RECORD.                  00409600
004097     CLOSE EMP-FILE.                                              00409700
004098     CALL "TAXCALC".                                              00409800
004099     CONTINUE                                                     00409900
004100     STOP RUN.                                                    00004100
