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
001800 01 NET-PAY           PIC 9(7)V99.                                00001800
001900 PROCEDURE DIVISION.                                              00001900
002000 MAIN-PARA.                                                       00002000
002100     PERFORM READ-EMPLOYEE.                                       00002100
002200     PERFORM CALC-GROSS.                                          00002200
002300     PERFORM CALC-NET.                                            00002300
002400     PERFORM DISPLAY-RESULT.                                      00002400
002500     GO TO END-PARA.                                              00002500
002600 READ-EMPLOYEE.                                                   00002600
002700     MOVE 12345 TO EMP-ID.                                        00002700
002800     MOVE "JANE SMITH" TO EMP-NAME.                               00002800
002900     MOVE 040.00 TO HOURS-WORKED.                                 00002900
003000     MOVE 025.00 TO HOURLY-RATE.                                  00003000
003100 CALC-GROSS.                                                      00003100
003200     COMPUTE GROSS-PAY = HOURS-WORKED * HOURLY-RATE.              00003200
003300 CALC-NET.                                                        00003300
003400     COMPUTE NET-PAY = GROSS-PAY - (GROSS-PAY * TAX-RATE).        00003400
003500 DISPLAY-RESULT.                                                  00003500
003600     DISPLAY "EMPLOYEE: " EMP-NAME.                               00003600
003700     DISPLAY "GROSS:    " GROSS-PAY.                              00003700
003800     DISPLAY "NET:      " NET-PAY.                                00003800
003900 END-PARA.                                                        00003900
004000     STOP RUN.                                                    00004000
