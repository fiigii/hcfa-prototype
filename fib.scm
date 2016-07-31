(define (fact x)
  (if (< x 1)
      1
      (* x (fact (- x 1)))))

(define (f n) (fact n))

(define a (f 10))
