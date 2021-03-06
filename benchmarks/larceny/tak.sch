;;; TAK -- A vanilla version of the TAKeuchi function.

(define (tak x y z)
  (if (not (< y x))
      z
      (let ((res (tak (tak (- x 1) y z)
                      (tak (- y 1) z x)
                      (tak (- z 1) x y))))
           res)
      ))

(tak 32 15 8)
